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
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.BodyTerm;
import jakarta.mail.search.FlagTerm;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class EmailService {

    @Inject
    EmailConfig config;

    private final Map<String, Store> imapStores = new ConcurrentHashMap<>();
    private final Map<String, String> cachedSpamFolders = new ConcurrentHashMap<>();
    private final Map<String, String> cachedDraftsFolders = new ConcurrentHashMap<>();

    // ── Account validation ──────────────────────────────────────────────

    private EmailConfig.AccountConfig getAccountConfig(String accountName) throws MessagingException {
        var ac = config.accounts().get(accountName);
        if (ac == null) {
            throw new MessagingException("Unknown account: " + accountName
                    + ". Available: " + config.accounts().keySet());
        }
        return ac;
    }

    public Set<String> getAccountNames() {
        return config.accounts().keySet();
    }

    // ── IMAP connection ──────────────────────────────────────────────────

    private synchronized Store getImapStore(String accountName) throws MessagingException {
        var existing = imapStores.get(accountName);
        if (existing != null) {
            if (isAlive(existing)) {
                return existing;
            }
            try { existing.close(); } catch (MessagingException ignored) {}
            imapStores.remove(accountName);
        }
        return connectImapStore(accountName);
    }

    private boolean isAlive(Store store) {
        try {
            store.getDefaultFolder();
            return store.isConnected();
        } catch (MessagingException e) {
            return false;
        }
    }

    private Store connectImapStore(String accountName) throws MessagingException {
        var ac = getAccountConfig(accountName);
        var imapCfg = ac.imap();

        var props = new Properties();
        var protocol = imapCfg.ssl() ? "imaps" : "imap";
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".host", imapCfg.host());
        props.put("mail." + protocol + ".port", String.valueOf(imapCfg.port()));
        if (imapCfg.ssl()) {
            props.put("mail." + protocol + ".ssl.enable", "true");
        }

        var session = Session.getInstance(props);
        var store = session.getStore(protocol);
        store.connect(imapCfg.username(), imapCfg.password());
        imapStores.put(accountName, store);
        return store;
    }

    @PreDestroy
    void cleanup() {
        for (var entry : imapStores.entrySet()) {
            try {
                var store = entry.getValue();
                if (store != null && store.isConnected()) {
                    store.close();
                }
            } catch (MessagingException ignored) {
            }
        }
        imapStores.clear();
    }

    // ── Flag helpers ──────────────────────────────────────────────────────

    private static boolean isForwarded(Message m) throws MessagingException {
        for (var flag : m.getFlags().getUserFlags()) {
            if ("$Forwarded".equalsIgnoreCase(flag)) return true;
        }
        return false;
    }

    // ── Folder operations ────────────────────────────────────────────────

    public String createFolder(String account, String folderName) throws MessagingException {
        var store = getImapStore(account);
        var folder = store.getFolder(folderName);
        if (folder.exists()) {
            return "Folder already exists: " + folderName;
        }
        if (folder.create(Folder.HOLDS_MESSAGES)) {
            return "Created folder: " + folderName;
        }
        throw new MessagingException("Server refused to create folder: " + folderName);
    }

    public List<String> listFolders(String account) throws MessagingException {
        var store = getImapStore(account);
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

    public List<FolderInfo> listFolderTree(String account) throws MessagingException {
        var store = getImapStore(account);
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

    public String getSpamFolder(String account) throws MessagingException {
        var cached = cachedSpamFolders.get(account);
        if (cached != null) return cached;

        var store = getImapStore(account);
        for (var candidate : SPAM_FOLDER_CANDIDATES) {
            var folder = store.getFolder(candidate);
            if (folder.exists()) {
                cachedSpamFolders.put(account, candidate);
                return candidate;
            }
        }
        return null;
    }

    public void setSpamFolder(String account, String folderName) {
        cachedSpamFolders.put(account, folderName);
    }

    public void moveToSpam(String account, String sourceFolderName, long uid, boolean markRead)
            throws MessagingException {
        moveToSpam(account, sourceFolderName, List.of(uid), markRead);
    }

    public int moveToSpam(String account, String sourceFolderName, List<Long> uids, boolean markRead)
            throws MessagingException {
        var spamFolder = getSpamFolder(account);
        if (spamFolder == null) {
            throw new MessagingException("No spam folder detected. Use setSpamFolder to configure one.");
        }
        return moveEmails(account, sourceFolderName, uids, spamFolder, markRead);
    }

    // ── Drafts folder detection ────────────────────────────────────────

    private static final List<String> DRAFTS_FOLDER_CANDIDATES = List.of(
            "Drafts", "[Gmail]/Drafts", "Draft", "DRAFT", "INBOX.Drafts", "INBOX.Draft");

    public String getDraftsFolder(String account) throws MessagingException {
        var cached = cachedDraftsFolders.get(account);
        if (cached != null) return cached;

        var store = getImapStore(account);
        for (var candidate : DRAFTS_FOLDER_CANDIDATES) {
            var folder = store.getFolder(candidate);
            if (folder.exists()) {
                cachedDraftsFolders.put(account, candidate);
                return candidate;
            }
        }

        // Fallback: scan all folders for one named "drafts" (case-insensitive)
        for (var folder : store.getDefaultFolder().list("*")) {
            var name = folder.getFullName();
            var leaf = name.contains(String.valueOf(folder.getSeparator()))
                    ? name.substring(name.lastIndexOf(folder.getSeparator()) + 1)
                    : name;
            if (leaf.equalsIgnoreCase("Drafts") || leaf.equalsIgnoreCase("Draft")) {
                cachedDraftsFolders.put(account, name);
                return name;
            }
        }
        return null;
    }

    public void setDraftsFolder(String account, String folderName) {
        cachedDraftsFolders.put(account, folderName);
    }

    public void saveDraft(String account, String to, String cc, String bcc,
                          String subject, String body,
                          String inReplyToFolder, long inReplyToUid) throws MessagingException {
        var ac = getAccountConfig(account);
        var session = Session.getInstance(new Properties());
        var draft = new MimeMessage(session);
        draft.setFrom(new InternetAddress(ac.smtp().username()));
        draft.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        if (cc != null) {
            draft.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc));
        }
        if (bcc != null) {
            draft.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc));
        }
        draft.setSubject(subject);
        draft.setText(body);

        // Thread as reply if original message info is provided
        if (inReplyToFolder != null && !inReplyToFolder.isEmpty() && inReplyToUid > 0) {
            var store = getImapStore(account);
            var origFolder = store.getFolder(inReplyToFolder);
            origFolder.open(Folder.READ_ONLY);
            try {
                var uf = (UIDFolder) origFolder;
                var original = uf.getMessageByUID(inReplyToUid);
                if (original != null) {
                    var messageId = original.getHeader("Message-ID");
                    if (messageId != null && messageId.length > 0) {
                        draft.setHeader("In-Reply-To", messageId[0]);
                        var origRefs = original.getHeader("References");
                        var refs = (origRefs != null && origRefs.length > 0 ? origRefs[0] + " " : "")
                                + messageId[0];
                        draft.setHeader("References", refs);
                    }
                }
            } finally {
                origFolder.close(false);
            }
        }

        var draftsName = getDraftsFolder(account);
        if (draftsName == null) {
            throw new MessagingException("No Drafts folder detected. Use setDraftsFolder to configure one.");
        }

        var store = getImapStore(account);
        var draftsFolder = store.getFolder(draftsName);
        draftsFolder.open(Folder.READ_WRITE);
        try {
            draft.setFlag(Flags.Flag.SEEN, true);
            draft.setFlag(Flags.Flag.DRAFT, true);
            draftsFolder.appendMessages(new Message[]{draft});
        } finally {
            draftsFolder.close(false);
        }
    }

    // ── List emails ──────────────────────────────────────────────────────

    public record EmailHeader(long uid, String subject, String from, String date,
                               boolean seen, boolean answered, boolean forwarded) {}

    public List<EmailHeader> listEmails(String account, String folderName, int offset, int limit)
            throws MessagingException {
        var store = getImapStore(account);
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
                boolean answered = m.isSet(Flags.Flag.ANSWERED);
                boolean forwarded = isForwarded(m);
                result.add(new EmailHeader(uf.getUID(m), m.getSubject(), from, date, seen, answered, forwarded));
            }
            return result;
        } finally {
            folder.close(false);
        }
    }

    // ── Read a single email ──────────────────────────────────────────────

    public record EmailContent(String subject, String from, String to, String date,
                               boolean seen, boolean answered, boolean forwarded,
                               String body, List<String> attachments) {}

    public EmailContent readEmail(String account, String folderName, long uid)
            throws MessagingException, IOException {
        var store = getImapStore(account);
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

            boolean answered = message.isSet(Flags.Flag.ANSWERED);
            boolean forwarded = isForwarded(message);
            return new EmailContent(message.getSubject(), from, to, date, seen, answered, forwarded, body, attachments);
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
        if (part.isMimeType("message/rfc822")) {
            return extractText((Part) part.getContent());
        }
        if (part.isMimeType("multipart/alternative")) {
            var mp = (Multipart) part.getContent();
            String fallback = null;
            for (int i = 0; i < mp.getCount(); i++) {
                var bp = mp.getBodyPart(i);
                String text = extractText(bp);
                if (text != null) {
                    if (bp.isMimeType("text/plain")) return text;
                    if (fallback == null) fallback = text;
                }
            }
            return fallback;
        }
        if (part.isMimeType("multipart/*")) {
            var mp = (Multipart) part.getContent();
            var parts = new ArrayList<String>();
            for (int i = 0; i < mp.getCount(); i++) {
                var text = extractText(mp.getBodyPart(i));
                if (text != null) parts.add(text);
            }
            return parts.isEmpty() ? null : String.join("\n\n", parts);
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
        } else if (part.isMimeType("message/rfc822")) {
            names.addAll(extractAttachmentNames((Part) part.getContent()));
        }
        return names;
    }

    // ── Move emails ─────────────────────────────────────────────────────

    public void moveEmail(String account, String sourceFolderName, long uid, String targetFolderName,
                          boolean markRead) throws MessagingException {
        moveEmails(account, sourceFolderName, List.of(uid), targetFolderName, markRead);
    }

    public int moveEmails(String account, String sourceFolderName, List<Long> uids, String targetFolderName,
                          boolean markRead) throws MessagingException {
        var store = getImapStore(account);
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
            if (markRead) {
                for (var m : msgArray) {
                    m.setFlag(Flags.Flag.SEEN, true);
                }
            }
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

    public record BatchMoveResult(int totalMoved, Map<String, Integer> perFolder) {}

    public BatchMoveResult batchMoveEmails(String account, String sourceFolderName,
                                           Map<String, List<Long>> targetToUids, boolean markRead)
            throws MessagingException {
        var store = getImapStore(account);
        var sourceFolder = store.getFolder(sourceFolderName);
        sourceFolder.open(Folder.READ_WRITE);
        try {
            var uf = (UIDFolder) sourceFolder;
            int totalMoved = 0;
            var perFolder = new LinkedHashMap<String, Integer>();

            for (var entry : targetToUids.entrySet()) {
                var targetFolder = store.getFolder(entry.getKey());
                var messages = new ArrayList<Message>();
                for (long uid : entry.getValue()) {
                    var m = uf.getMessageByUID(uid);
                    if (m != null) messages.add(m);
                }
                if (messages.isEmpty()) continue;

                var msgArray = messages.toArray(new Message[0]);
                if (markRead) {
                    for (var m : msgArray) {
                        m.setFlag(Flags.Flag.SEEN, true);
                    }
                }
                sourceFolder.copyMessages(msgArray, targetFolder);
                for (var m : msgArray) {
                    m.setFlag(Flags.Flag.DELETED, true);
                }
                totalMoved += msgArray.length;
                perFolder.put(entry.getKey(), msgArray.length);
            }

            sourceFolder.expunge();
            return new BatchMoveResult(totalMoved, perFolder);
        } finally {
            sourceFolder.close(false);
        }
    }

    // ── Delete email (move to Trash) ─────────────────────────────────────

    public void deleteEmail(String account, String folderName, long uid) throws MessagingException {
        var store = getImapStore(account);
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

    public List<EmailHeader> searchEmails(String account, String folderName, String query, int limit)
            throws MessagingException {
        var store = getImapStore(account);
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
                boolean answered = m.isSet(Flags.Flag.ANSWERED);
                boolean forwarded = isForwarded(m);
                result.add(new EmailHeader(uf.getUID(m), m.getSubject(), from, date, seen, answered, forwarded));
            }
            return result;
        } finally {
            folder.close(false);
        }
    }

    // ── Send email ───────────────────────────────────────────────────────

    private MimeMessage buildSmtpMessage(String account) throws MessagingException {
        var ac = getAccountConfig(account);
        var smtpCfg = ac.smtp();

        var props = new Properties();
        props.put("mail.smtp.host", smtpCfg.host());
        props.put("mail.smtp.port", String.valueOf(smtpCfg.port()));
        props.put("mail.smtp.auth", "true");
        if (smtpCfg.starttls()) {
            props.put("mail.smtp.starttls.enable", "true");
        }

        var session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(smtpCfg.username(), smtpCfg.password());
            }
        });

        var message = new MimeMessage(session);
        message.setFrom(new InternetAddress(smtpCfg.username()));
        return message;
    }

    public void sendEmail(String account, String to, String cc, String bcc,
                          String subject, String body) throws MessagingException {
        var message = buildSmtpMessage(account);
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        if (cc != null) {
            message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc));
        }
        if (bcc != null) {
            message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc));
        }
        message.setSubject(subject);
        message.setText(body);
        Transport.send(message);
    }

    public void replyEmail(String account, String folderName, long uid, String body, boolean replyAll,
                           String extraCc, String extraBcc) throws MessagingException {
        var ac = getAccountConfig(account);
        var store = getImapStore(account);
        var folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);
        try {
            var uf = (UIDFolder) folder;
            var original = uf.getMessageByUID(uid);
            if (original == null) throw new MessagingException("No message with UID " + uid);

            var reply = buildSmtpMessage(account);

            // Threading headers
            var messageId = original.getHeader("Message-ID");
            if (messageId != null && messageId.length > 0) {
                reply.setHeader("In-Reply-To", messageId[0]);
                var origRefs = original.getHeader("References");
                var refs = (origRefs != null && origRefs.length > 0 ? origRefs[0] + " " : "") + messageId[0];
                reply.setHeader("References", refs);
            }

            // Subject
            var subject = original.getSubject();
            if (subject == null) subject = "";
            if (!subject.regionMatches(true, 0, "Re: ", 0, 4)) {
                subject = "Re: " + subject;
            }
            reply.setSubject(subject);

            // To: Reply-To if set, else From
            var replyTo = original.getReplyTo();
            if (replyTo != null && replyTo.length > 0) {
                reply.setRecipients(Message.RecipientType.TO, replyTo);
            } else {
                reply.setRecipients(Message.RecipientType.TO, original.getFrom());
            }

            // Reply-All: add original To and Cc as Cc, excluding our own address
            if (replyAll) {
                var senderAddress = ac.smtp().username().toLowerCase();
                var ccList = new ArrayList<Address>();
                var origTo = original.getRecipients(Message.RecipientType.TO);
                if (origTo != null) {
                    for (var addr : origTo) {
                        if (!addr.toString().toLowerCase().contains(senderAddress)) {
                            ccList.add(addr);
                        }
                    }
                }
                var origCc = original.getRecipients(Message.RecipientType.CC);
                if (origCc != null) {
                    for (var addr : origCc) {
                        if (!addr.toString().toLowerCase().contains(senderAddress)) {
                            ccList.add(addr);
                        }
                    }
                }
                if (!ccList.isEmpty()) {
                    reply.setRecipients(Message.RecipientType.CC, ccList.toArray(new Address[0]));
                }
            }

            // Extra CC/BCC — addRecipients merges with any existing CC from reply-all
            if (extraCc != null) {
                reply.addRecipients(Message.RecipientType.CC, InternetAddress.parse(extraCc));
            }
            if (extraBcc != null) {
                reply.addRecipients(Message.RecipientType.BCC, InternetAddress.parse(extraBcc));
            }

            reply.setText(body);
            Transport.send(reply);
        } finally {
            folder.close(false);
        }
    }

    // ── Forward email ────────────────────────────────────────────────────

    public void forwardEmail(String account, String folderName, long uid, String to, String comment)
            throws MessagingException {
        var store = getImapStore(account);
        var folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);
        try {
            var uf = (UIDFolder) folder;
            var original = uf.getMessageByUID(uid);
            if (original == null) throw new MessagingException("No message with UID " + uid);

            var forward = buildSmtpMessage(account);

            var subject = original.getSubject();
            if (subject == null) subject = "";
            if (!subject.regionMatches(true, 0, "Fwd: ", 0, 5)) {
                subject = "Fwd: " + subject;
            }
            forward.setSubject(subject);
            forward.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));

            var multipart = new MimeMultipart();
            if (comment != null && !comment.isEmpty()) {
                var commentPart = new MimeBodyPart();
                commentPart.setText(comment);
                multipart.addBodyPart(commentPart);
            }
            var originalPart = new MimeBodyPart();
            originalPart.setContent(original, "message/rfc822");
            multipart.addBodyPart(originalPart);

            forward.setContent(multipart);
            Transport.send(forward);
        } finally {
            folder.close(false);
        }
    }

    // ── Unread email triage ─────────────────────────────────────────────

    public int getUnreadCount(String account, String folderName) throws MessagingException {
        var store = getImapStore(account);
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

    public record EmailSummary(long uid, boolean answered, boolean forwarded,
                               Map<String, String> headers) {}

    public record CompactEmailSummary(long uid, String from, String subject, String date,
                                      boolean answered, boolean forwarded, double spamScore,
                                      boolean hasListUnsubscribe, boolean fromReplyToMismatch) {}

    public List<CompactEmailSummary> summarizeEmailsCompact(String account, String folderName,
                                                              boolean unreadOnly, int offset, int limit)
            throws MessagingException {
        var store = getImapStore(account);
        var folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);
        try {
            var uf = (UIDFolder) folder;
            if (limit <= 0) limit = 20;

            Message[] messages;
            if (unreadOnly) {
                messages = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                if (messages.length == 0) return List.of();
                var fp = new FetchProfile();
                fp.add(FetchProfile.Item.ENVELOPE);
                fp.add(FetchProfile.Item.FLAGS);
                fp.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(messages, fp);
                Arrays.sort(messages, Comparator.comparingLong(m -> {
                    try { return uf.getUID(m); } catch (Exception e) { return 0; }
                }));
            } else {
                int total = folder.getMessageCount();
                if (total == 0) return List.of();
                int end = total - offset;
                int start = Math.max(1, end - limit + 1);
                if (end < 1) return List.of();
                messages = folder.getMessages(start, end);
                var fp = new FetchProfile();
                fp.add(FetchProfile.Item.ENVELOPE);
                fp.add(FetchProfile.Item.FLAGS);
                fp.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(messages, fp);
            }

            int count = Math.min(messages.length, limit);
            var result = new ArrayList<CompactEmailSummary>(count);
            for (int i = unreadOnly ? 0 : messages.length - 1;
                 unreadOnly ? i < count : i >= 0 && result.size() < count;
                 i += unreadOnly ? 1 : -1) {
                var m = messages[i];

                var fromAddrs = m.getFrom();
                var from = (fromAddrs != null && fromAddrs.length > 0) ? fromAddrs[0].toString() : "(unknown)";
                var subject = m.getSubject() != null ? m.getSubject() : "(no subject)";
                var date = m.getSentDate() != null ? m.getSentDate().toString() : "(no date)";

                double spamScore = 0;
                var scoreHeader = m.getHeader("X-Spam-Score");
                if (scoreHeader != null && scoreHeader.length > 0) {
                    try { spamScore = Double.parseDouble(scoreHeader[0].trim()); } catch (NumberFormatException ignored) {}
                }
                if (spamScore == 0) {
                    var statusHeader = m.getHeader("X-Spam-Status");
                    if (statusHeader != null && statusHeader.length > 0) {
                        var status = statusHeader[0];
                        int idx = status.indexOf("score=");
                        if (idx >= 0) {
                            var end = status.indexOf(' ', idx + 6);
                            if (end < 0) end = status.length();
                            try { spamScore = Double.parseDouble(status.substring(idx + 6, end)); } catch (NumberFormatException ignored) {}
                        }
                    }
                }

                var listUnsub = m.getHeader("List-Unsubscribe");
                boolean hasListUnsubscribe = listUnsub != null && listUnsub.length > 0;

                boolean fromReplyToMismatch = false;
                var replyTo = m.getReplyTo();
                if (replyTo != null && replyTo.length > 0 && fromAddrs != null && fromAddrs.length > 0) {
                    var fromStr = fromAddrs[0] instanceof InternetAddress ia ? ia.getAddress() : fromAddrs[0].toString();
                    var replyStr = replyTo[0] instanceof InternetAddress ia ? ia.getAddress() : replyTo[0].toString();
                    if (fromStr != null && replyStr != null) {
                        fromReplyToMismatch = !fromStr.equalsIgnoreCase(replyStr);
                    }
                }

                boolean answered = m.isSet(Flags.Flag.ANSWERED);
                boolean forwarded = isForwarded(m);
                result.add(new CompactEmailSummary(uf.getUID(m), from, subject, date,
                        answered, forwarded, spamScore, hasListUnsubscribe, fromReplyToMismatch));
            }
            return result;
        } finally {
            folder.close(false);
        }
    }

    public List<EmailSummary> summarizeEmails(String account, String folderName,
                                              boolean unreadOnly, int offset, int limit)
            throws MessagingException {
        var store = getImapStore(account);
        var folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);
        try {
            var uf = (UIDFolder) folder;
            if (limit <= 0) limit = 20;

            Message[] messages;
            if (unreadOnly) {
                messages = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                if (messages.length == 0) return List.of();
                var fp = new FetchProfile();
                fp.add(FetchProfile.Item.FLAGS);
                fp.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(messages, fp);
                Arrays.sort(messages, Comparator.comparingLong(m -> {
                    try { return uf.getUID(m); } catch (Exception e) { return 0; }
                }));
            } else {
                int total = folder.getMessageCount();
                if (total == 0) return List.of();
                int end = total - offset;
                int start = Math.max(1, end - limit + 1);
                if (end < 1) return List.of();
                messages = folder.getMessages(start, end);
                var fp = new FetchProfile();
                fp.add(FetchProfile.Item.FLAGS);
                fp.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(messages, fp);
            }

            int count = Math.min(messages.length, limit);
            var result = new ArrayList<EmailSummary>(count);
            for (int i = unreadOnly ? 0 : messages.length - 1;
                 unreadOnly ? i < count : i >= 0 && result.size() < count;
                 i += unreadOnly ? 1 : -1) {
                var m = messages[i];
                var headers = new LinkedHashMap<String, String>();
                for (var name : TRIAGE_HEADERS) {
                    var values = m.getHeader(name);
                    if (values != null && values.length > 0) {
                        headers.put(name, String.join("; ", values));
                    }
                }
                boolean answered = m.isSet(Flags.Flag.ANSWERED);
                boolean forwarded = isForwarded(m);
                result.add(new EmailSummary(uf.getUID(m), answered, forwarded, headers));
            }
            return result;
        } finally {
            folder.close(false);
        }
    }

    public record FullEmail(long uid, int unreadLeft, boolean answered, boolean forwarded,
                            Map<String, String> headers, String body, List<String> attachments) {}

    public FullEmail getNextUnreadEmail(String account, String folderName)
            throws MessagingException, IOException {
        var store = getImapStore(account);
        var folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);
        try {
            var uf = (UIDFolder) folder;
            var unreadTerm = new FlagTerm(new Flags(Flags.Flag.SEEN), false);
            var messages = folder.search(unreadTerm);
            if (messages.length == 0) return null;

            var fp = new FetchProfile();
            fp.add(FetchProfile.Item.FLAGS);
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

            boolean answered = message.isSet(Flags.Flag.ANSWERED);
            boolean forwarded = isForwarded(message);
            return new FullEmail(uf.getUID(message), messages.length, answered, forwarded, headers, body, attachments);
        } finally {
            folder.close(false);
        }
    }

    // ── Set flags on one or more emails ─────────────────────────────────

    public int setMessageFlags(String account, String folderName, List<Long> uids,
                               Boolean seen, Boolean answered, Boolean forwarded, Boolean flagged)
            throws MessagingException {
        var store = getImapStore(account);
        var folder = store.getFolder(folderName);
        folder.open(Folder.READ_WRITE);
        try {
            var uf = (UIDFolder) folder;
            int count = 0;
            for (long uid : uids) {
                var m = uf.getMessageByUID(uid);
                if (m == null) continue;
                if (seen != null) m.setFlag(Flags.Flag.SEEN, seen);
                if (answered != null) m.setFlag(Flags.Flag.ANSWERED, answered);
                if (forwarded != null) m.setFlags(new Flags("$Forwarded"), forwarded);
                if (flagged != null) m.setFlag(Flags.Flag.FLAGGED, flagged);
                count++;
            }
            return count;
        } finally {
            folder.close(false);
        }
    }
}
