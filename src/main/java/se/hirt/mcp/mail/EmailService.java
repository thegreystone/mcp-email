/*
 * Copyright (C) 2026 Marcus Hirt
 *
 * This software is free:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESSED OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package se.hirt.mcp.mail;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.BodyTerm;
import jakarta.mail.search.FlagTerm;

import java.io.IOException;
import java.util.*;

@ApplicationScoped
public class EmailService {

    @Inject
    EmailConfig config;

    private Store imapStore;
    private String cachedSpamFolder;

    // ── IMAP connection ──────────────────────────────────────────────────

    private synchronized Store getImapStore() throws MessagingException {
        if (imapStore != null && imapStore.isConnected()) {
            return imapStore;
        }
        var props = new Properties();
        var protocol = config.imap().ssl() ? "imaps" : "imap";
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".host", config.imap().host());
        props.put("mail." + protocol + ".port", String.valueOf(config.imap().port()));
        if (config.imap().ssl()) {
            props.put("mail." + protocol + ".ssl.enable", "true");
        }

        var session = Session.getInstance(props);
        imapStore = session.getStore(protocol);
        imapStore.connect(config.imap().username(), config.imap().password());
        return imapStore;
    }

    @PreDestroy
    void cleanup() {
        try {
            if (imapStore != null && imapStore.isConnected()) {
                imapStore.close();
            }
        } catch (MessagingException ignored) {
        }
    }

    // ── Folder operations ────────────────────────────────────────────────

    public String createFolder(String folderName) throws MessagingException {
        var store = getImapStore();
        var folder = store.getFolder(folderName);
        if (folder.exists()) {
            return "Folder already exists: " + folderName;
        }
        if (folder.create(Folder.HOLDS_MESSAGES)) {
            return "Created folder: " + folderName;
        }
        throw new MessagingException("Server refused to create folder: " + folderName);
    }

    public List<String> listFolders() throws MessagingException {
        var store = getImapStore();
        var folders = store.getDefaultFolder().list("*");
        var result = new ArrayList<String>();
        for (var f : folders) {
            if ((f.getType() & Folder.HOLDS_MESSAGES) != 0) {
                result.add(f.getFullName());
            }
        }
        return result;
    }

    public record FolderInfo(String fullName, char separator, int totalMessages, int unreadMessages,
                             boolean holdsMessages, boolean holdsFolders) {}

    public List<FolderInfo> listFolderTree() throws MessagingException {
        var store = getImapStore();
        var folders = store.getDefaultFolder().list("*");
        var result = new ArrayList<FolderInfo>();
        for (var f : folders) {
            int type = f.getType();
            boolean holdsMessages = (type & Folder.HOLDS_MESSAGES) != 0;
            int total = 0;
            int unread = 0;
            if (holdsMessages) {
                f.open(Folder.READ_ONLY);
                try {
                    total = f.getMessageCount();
                    unread = f.getUnreadMessageCount();
                } finally {
                    f.close(false);
                }
            }
            result.add(new FolderInfo(f.getFullName(), f.getSeparator(), total, unread,
                    holdsMessages, (type & Folder.HOLDS_FOLDERS) != 0));
        }
        return result;
    }

    // ── Spam folder detection ─────────────────────────────────────────────

    private static final List<String> SPAM_FOLDER_CANDIDATES = List.of(
            "Spam", "Junk", "[Gmail]/Spam", "Junk E-mail", "Bulk Mail", "Junk Email");

    public String getSpamFolder() throws MessagingException {
        if (cachedSpamFolder != null) return cachedSpamFolder;

        var store = getImapStore();
        for (var candidate : SPAM_FOLDER_CANDIDATES) {
            var folder = store.getFolder(candidate);
            if (folder.exists()) {
                cachedSpamFolder = candidate;
                return cachedSpamFolder;
            }
        }
        return null;
    }

    public void setSpamFolder(String folderName) {
        cachedSpamFolder = folderName;
    }

    public void moveToSpam(String sourceFolderName, long uid) throws MessagingException {
        moveToSpam(sourceFolderName, List.of(uid));
    }

    public int moveToSpam(String sourceFolderName, List<Long> uids) throws MessagingException {
        var spamFolder = getSpamFolder();
        if (spamFolder == null) {
            throw new MessagingException("No spam folder detected. Use setSpamFolder to configure one.");
        }
        return moveEmails(sourceFolderName, uids, spamFolder);
    }

    // ── List emails ──────────────────────────────────────────────────────

    public record EmailHeader(long uid, String subject, String from, String date, boolean seen) {}

    public List<EmailHeader> listEmails(String folderName, int offset, int limit) throws MessagingException {
        var store = getImapStore();
        var folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);
        try {
            var uf = (UIDFolder) folder;
            int total = folder.getMessageCount();
            if (total == 0) return List.of();

            int end = total - offset;
            int start = Math.max(1, end - limit + 1);
            if (end < 1) return List.of();

            var messages = folder.getMessages(start, end);
            var fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.FLAGS);
            fp.add(UIDFolder.FetchProfileItem.UID);
            folder.fetch(messages, fp);

            var result = new ArrayList<EmailHeader>();
            for (int i = messages.length - 1; i >= 0; i--) {
                var m = messages[i];
                var from = m.getFrom() != null && m.getFrom().length > 0
                        ? m.getFrom()[0].toString() : "(unknown)";
                var date = m.getSentDate() != null ? m.getSentDate().toString() : "(no date)";
                boolean seen = m.isSet(Flags.Flag.SEEN);
                result.add(new EmailHeader(uf.getUID(m), m.getSubject(), from, date, seen));
            }
            return result;
        } finally {
            folder.close(false);
        }
    }

    // ── Read a single email ──────────────────────────────────────────────

    public record EmailContent(String subject, String from, String to, String date,
                               boolean seen, String body, List<String> attachments) {}

    public EmailContent readEmail(String folderName, long uid) throws MessagingException, IOException {
        var store = getImapStore();
        var folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);
        try {
            var uf = (UIDFolder) folder;
            var message = uf.getMessageByUID(uid);
            if (message == null) throw new MessagingException("No message with UID " + uid);

            var from = message.getFrom() != null && message.getFrom().length > 0
                    ? message.getFrom()[0].toString() : "(unknown)";
            var to = InternetAddress.toString(message.getRecipients(Message.RecipientType.TO));
            var date = message.getSentDate() != null ? message.getSentDate().toString() : "(no date)";
            boolean seen = message.isSet(Flags.Flag.SEEN);

            var body = extractText(message);
            var attachments = extractAttachmentNames(message);

            return new EmailContent(message.getSubject(), from, to, date, seen, body, attachments);
        } finally {
            folder.close(false);
        }
    }

    private String extractText(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            return (String) part.getContent();
        }
        if (part.isMimeType("text/html")) {
            return "[HTML] " + part.getContent();
        }
        if (part.isMimeType("multipart/*")) {
            var mp = (Multipart) part.getContent();
            String html = null;
            for (int i = 0; i < mp.getCount(); i++) {
                var bp = mp.getBodyPart(i);
                String text = extractText(bp);
                if (text != null) {
                    if (bp.isMimeType("text/plain")) return text;
                    if (bp.isMimeType("text/html")) html = text;
                }
            }
            return html;
        }
        return null;
    }

    private List<String> extractAttachmentNames(Part part) throws MessagingException, IOException {
        var names = new ArrayList<String>();
        if (part.isMimeType("multipart/*")) {
            var mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                var bp = mp.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(bp.getDisposition()) && bp.getFileName() != null) {
                    names.add(bp.getFileName());
                }
                names.addAll(extractAttachmentNames(bp));
            }
        }
        return names;
    }

    // ── Move emails ─────────────────────────────────────────────────────

    public void moveEmail(String sourceFolderName, long uid, String targetFolderName)
            throws MessagingException {
        moveEmails(sourceFolderName, List.of(uid), targetFolderName);
    }

    public int moveEmails(String sourceFolderName, List<Long> uids, String targetFolderName)
            throws MessagingException {
        var store = getImapStore();
        var sourceFolder = store.getFolder(sourceFolderName);
        var targetFolder = store.getFolder(targetFolderName);
        sourceFolder.open(Folder.READ_WRITE);
        try {
            var uf = (UIDFolder) sourceFolder;
            var messages = new ArrayList<Message>();
            for (long uid : uids) {
                var m = uf.getMessageByUID(uid);
                if (m != null) {
                    messages.add(m);
                }
            }
            if (messages.isEmpty()) return 0;

            var msgArray = messages.toArray(new Message[0]);
            sourceFolder.copyMessages(msgArray, targetFolder);
            for (var m : msgArray) {
                m.setFlag(Flags.Flag.DELETED, true);
            }
            sourceFolder.expunge();
            return msgArray.length;
        } finally {
            sourceFolder.close(false);
        }
    }

    // ── Delete email (move to Trash) ─────────────────────────────────────

    public void deleteEmail(String folderName, long uid) throws MessagingException {
        var store = getImapStore();
        Folder trashFolder = null;
        for (var name : List.of("[Gmail]/Trash", "Trash", "Deleted Items", "Deleted")) {
            trashFolder = store.getFolder(name);
            if (trashFolder.exists()) break;
            trashFolder = null;
        }

        var sourceFolder = store.getFolder(folderName);
        sourceFolder.open(Folder.READ_WRITE);
        try {
            var uf = (UIDFolder) sourceFolder;
            var message = uf.getMessageByUID(uid);
            if (message == null) throw new MessagingException("No message with UID " + uid);

            if (trashFolder != null) {
                sourceFolder.copyMessages(new Message[]{message}, trashFolder);
            }
            message.setFlag(Flags.Flag.DELETED, true);
            sourceFolder.expunge();
        } finally {
            sourceFolder.close(false);
        }
    }

    // ── Search emails ────────────────────────────────────────────────────

    public List<EmailHeader> searchEmails(String folderName, String query, int limit) throws MessagingException {
        var store = getImapStore();
        var folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);
        try {
            var uf = (UIDFolder) folder;
            SearchTerm term = new OrTerm(new SearchTerm[]{
                    new SubjectTerm(query),
                    new FromStringTerm(query),
                    new BodyTerm(query)
            });
            var messages = folder.search(term);

            var fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.FLAGS);
            fp.add(UIDFolder.FetchProfileItem.UID);
            folder.fetch(messages, fp);

            var result = new ArrayList<EmailHeader>();
            int count = Math.min(messages.length, limit > 0 ? limit : 20);
            for (int i = messages.length - 1; i >= 0 && result.size() < count; i--) {
                var m = messages[i];
                var from = m.getFrom() != null && m.getFrom().length > 0
                        ? m.getFrom()[0].toString() : "(unknown)";
                var date = m.getSentDate() != null ? m.getSentDate().toString() : "(no date)";
                boolean seen = m.isSet(Flags.Flag.SEEN);
                result.add(new EmailHeader(uf.getUID(m), m.getSubject(), from, date, seen));
            }
            return result;
        } finally {
            folder.close(false);
        }
    }

    // ── Send email ───────────────────────────────────────────────────────

    public void sendEmail(String to, String subject, String body) throws MessagingException {
        var props = new Properties();
        props.put("mail.smtp.host", config.smtp().host());
        props.put("mail.smtp.port", String.valueOf(config.smtp().port()));
        props.put("mail.smtp.auth", "true");
        if (config.smtp().starttls()) {
            props.put("mail.smtp.starttls.enable", "true");
        }

        var session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.smtp().username(), config.smtp().password());
            }
        });

        var message = new MimeMessage(session);
        message.setFrom(new InternetAddress(config.smtp().username()));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(subject);
        message.setText(body);
        Transport.send(message);
    }

    // ── Unread email triage ─────────────────────────────────────────────

    public int getUnreadCount(String folderName) throws MessagingException {
        var store = getImapStore();
        var folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);
        try {
            return folder.getUnreadMessageCount();
        } finally {
            folder.close(false);
        }
    }

    private static final List<String> TRIAGE_HEADERS = List.of(
            "From", "Reply-To", "Return-Path", "To", "Cc",
            "Subject", "Date", "List-Unsubscribe",
            "X-Spam-Status", "X-Spam-Flag", "X-Spam-Score",
            "Authentication-Results", "X-Mailer", "X-Priority");

    public record EmailSummary(long uid, Map<String, String> headers) {}

    public List<EmailSummary> summarizeUnread(String folderName, int limit) throws MessagingException {
        var store = getImapStore();
        var folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);
        try {
            var uf = (UIDFolder) folder;
            var unreadTerm = new FlagTerm(new Flags(Flags.Flag.SEEN), false);
            var messages = folder.search(unreadTerm);
            if (messages.length == 0) return List.of();

            var fp = new FetchProfile();
            fp.add(UIDFolder.FetchProfileItem.UID);
            folder.fetch(messages, fp);

            Arrays.sort(messages, Comparator.comparingLong(m -> {
                try { return uf.getUID(m); } catch (Exception e) { return 0; }
            }));

            int count = Math.min(messages.length, limit > 0 ? limit : 20);
            var result = new ArrayList<EmailSummary>(count);
            for (int i = 0; i < count; i++) {
                var m = messages[i];
                var headers = new LinkedHashMap<String, String>();
                for (var name : TRIAGE_HEADERS) {
                    var values = m.getHeader(name);
                    if (values != null && values.length > 0) {
                        headers.put(name, String.join("; ", values));
                    }
                }
                result.add(new EmailSummary(uf.getUID(m), headers));
            }
            return result;
        } finally {
            folder.close(false);
        }
    }

    public record FullEmail(long uid, int unreadLeft,
                            Map<String, String> headers, String body, List<String> attachments) {}

    public FullEmail getNextUnreadEmail(String folderName) throws MessagingException, IOException {
        var store = getImapStore();
        var folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);
        try {
            var uf = (UIDFolder) folder;
            var unreadTerm = new FlagTerm(new Flags(Flags.Flag.SEEN), false);
            var messages = folder.search(unreadTerm);
            if (messages.length == 0) return null;

            var fp = new FetchProfile();
            fp.add(UIDFolder.FetchProfileItem.UID);
            folder.fetch(messages, fp);

            Arrays.sort(messages, Comparator.comparingLong(m -> {
                try { return uf.getUID(m); } catch (Exception e) { return 0; }
            }));

            var message = messages[0];

            var headers = new LinkedHashMap<String, String>();
            var allHeaders = message.getAllHeaders();
            while (allHeaders.hasMoreElements()) {
                var h = allHeaders.nextElement();
                headers.merge(h.getName(), h.getValue(),
                        (old, val) -> old + "\n" + val);
            }

            var body = extractText(message);
            var attachments = extractAttachmentNames(message);

            return new FullEmail(uf.getUID(message), messages.length, headers, body, attachments);
        } finally {
            folder.close(false);
        }
    }

    // ── Mark as read / unread ────────────────────────────────────────────

    public void markAs(String folderName, long uid, boolean seen) throws MessagingException {
        var store = getImapStore();
        var folder = store.getFolder(folderName);
        folder.open(Folder.READ_WRITE);
        try {
            var uf = (UIDFolder) folder;
            var message = uf.getMessageByUID(uid);
            if (message == null) throw new MessagingException("No message with UID " + uid);
            message.setFlag(Flags.Flag.SEEN, seen);
        } finally {
            folder.close(false);
        }
    }
}
