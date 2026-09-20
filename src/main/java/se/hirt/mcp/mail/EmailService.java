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
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.BodyTerm;
import jakarta.mail.search.FlagTerm;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class EmailService {

	@Inject
	EmailConfig config;

	private final Map<String, Store> imapStores = new ConcurrentHashMap<>();
	private final Map<String, String> cachedSpamFolders = new ConcurrentHashMap<>();
	private final Map<String, String> cachedDraftsFolders = new ConcurrentHashMap<>();
	private final Map<String, String> cachedTrashFolders = new ConcurrentHashMap<>();

	// ── Account validation ──────────────────────────────────────────────

	private EmailConfig.AccountConfig getAccountConfig(String accountName) throws MessagingException {
		var ac = config.accounts().get(accountName);
		if (ac == null) {
			throw new MessagingException(
					"Unknown account: " + accountName + ". Available: " + config.accounts().keySet());
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
			try {
				existing.close();
			} catch (MessagingException ignored) {
			}
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

	/**
	 * Applies {@link EmailConfig#networkTimeout()} to the connect, read and write timeouts of a
	 * Jakarta Mail protocol ({@code imap}, {@code imaps} or {@code smtp}). Jakarta Mail takes
	 * milliseconds and defaults to no timeout at all.
	 */
	private void applyNetworkTimeout(Properties props, String protocol) {
		int seconds = config.networkTimeout();
		if (seconds <= 0) {
			return;
		}
		var millis = String.valueOf(seconds * 1000L);
		props.put("mail." + protocol + ".connectiontimeout", millis);
		props.put("mail." + protocol + ".timeout", millis);
		props.put("mail." + protocol + ".writetimeout", millis);
	}

	/**
	 * An optional setting as the user meant it. Blank means unset. So does a literal
	 * {@code ${...}}: an MCP bundle host may pass an empty optional setting through as its
	 * unexpanded placeholder.
	 */
	static Optional<String> setting(Optional<String> value) {
		return value.map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("${"));
	}

	/**
	 * The From address for the account: the configured {@code from}, else the SMTP username. Parsed
	 * leniently, so a login that is not an email address still yields something rather than failing
	 * every send; a value with more than one address is rejected.
	 */
	static InternetAddress fromAddress(EmailConfig.AccountConfig ac) throws MessagingException {
		var value = setting(ac.from()).orElse(ac.smtp().username());
		var parsed = InternetAddress.parse(value, false);
		if (parsed.length != 1) {
			throw new MessagingException("The From address must be a single address, got: " + value);
		}
		return parsed[0];
	}

	/**
	 * The addresses that belong to this account, lower-cased: the From address, and the IMAP and
	 * SMTP usernames when they are email addresses. Reply-all leaves these out of the Cc list.
	 */
	private static Set<String> ownAddresses(EmailConfig.AccountConfig ac) throws MessagingException {
		var own = new HashSet<String>();
		own.add(fromAddress(ac).getAddress().toLowerCase(Locale.ROOT));
		for (var username : List.of(ac.smtp().username(), ac.imap().username())) {
			if (username != null && username.contains("@")) {
				own.add(username.trim().toLowerCase(Locale.ROOT));
			}
		}
		return own;
	}

	private static boolean isOwnAddress(Address addr, Set<String> own) {
		var address = addr instanceof InternetAddress ia ? ia.getAddress() : addr.toString();
		return address != null && own.contains(address.toLowerCase(Locale.ROOT));
	}

	/**
	 * Whether IMAP uses TLS from the first byte. An explicit setting wins; otherwise it follows the
	 * port, so that port 143 (plain IMAP) works without also having to set the flag, as it would in
	 * a mail client.
	 */
	static boolean imapSsl(EmailConfig.ImapConfig cfg) {
		return cfg.ssl().orElse(cfg.port() != 143);
	}

	/**
	 * Whether SMTP uses implicit TLS ("SMTPS"). Explicit setting wins; otherwise on for port 465
	 * only.
	 */
	static boolean smtpSsl(EmailConfig.SmtpConfig cfg) {
		return cfg.ssl().orElse(cfg.port() == 465);
	}

	private Store connectImapStore(String accountName) throws MessagingException {
		var ac = getAccountConfig(accountName);
		var imapCfg = ac.imap();

		var props = new Properties();
		boolean ssl = imapSsl(imapCfg);
		var protocol = ssl ? "imaps" : "imap";
		props.put("mail.store.protocol", protocol);
		props.put("mail." + protocol + ".host", imapCfg.host());
		props.put("mail." + protocol + ".port", String.valueOf(imapCfg.port()));
		if (ssl) {
			props.put("mail." + protocol + ".ssl.enable", "true");
		} else {
			// Plain IMAP (port 143): upgrade with STARTTLS when the server offers it. Not required, since
			// ssl=false is also how one talks to a local or test server that has no TLS at all.
			props.put("mail." + protocol + ".starttls.enable", "true");
		}
		applyNetworkTimeout(props, protocol);

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
			if ("$Forwarded".equalsIgnoreCase(flag))
				return true;
		}
		return false;
	}

	// ── Folder operations ────────────────────────────────────────────────

	public String createFolder(String account, String folderName) throws MessagingException {
		var store = getImapStore(account);
		var folder = store.getFolder(folderName);
		if (folder.exists()) {
			throw new MessagingException("Folder already exists: " + folderName);
		}
		if (folder.create(Folder.HOLDS_MESSAGES)) {
			return "Created folder: " + folderName;
		}
		throw new MessagingException("Server refused to create folder: " + folderName);
	}

	public boolean folderExists(String account, String folderName) throws MessagingException {
		return getImapStore(account).getFolder(folderName).exists();
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
			boolean holdsMessages, boolean holdsFolders) {
	}

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
			result.add(new FolderInfo(f.getFullName(), f.getSeparator(), total, unread, holdsMessages,
					(type & Folder.HOLDS_FOLDERS) != 0));
		}
		return result;
	}

	// ── Special folder resolution (spam, drafts) ──────────────────────────

	/**
	 * Resolves a special folder for an account. Resolution order:
	 * <ol>
	 * <li>a name set for this session with the corresponding set*Folder tool (always wins),</li>
	 * <li>the configured name (EMAIL_ACCOUNTS_&lt;NAME&gt;_DRAFTS_FOLDER / _SPAM_FOLDER); a
	 * configured folder that does not exist on the server is an error rather than a silent
	 * fallback, so that the user learns about the misconfiguration and the LLM can work around it
	 * with the set*Folder tool,</li>
	 * <li>the RFC 6154 special-use attribute (e.g. {@code \Drafts}) reported by the server in
	 * LIST,</li>
	 * <li>well-known folder names tried in order,</li>
	 * <li>any folder whose leaf name matches one of the given names, which handles servers that
	 * nest everything under a namespace prefix such as {@code INBOX.INBOX.Drafts}.</li>
	 * </ol>
	 *
	 * @return the full folder name, or {@code null} if nothing was found
	 */
	private String resolveSpecialFolder(
		String account, Map<String, String> cache, Optional<String> configured, String kind, String setterTool,
		String specialUse, List<String> candidates, List<String> leafNames) throws MessagingException {
		var cached = cache.get(account);
		if (cached != null)
			return cached;

		var store = getImapStore(account);

		var configuredName = setting(configured).orElse(null);
		if (configuredName != null) {
			if (!store.getFolder(configuredName).exists()) {
				throw new MessagingException("The configured " + kind + " folder '" + configuredName
						+ "' does not exist on the server for account " + account
						+ ". Fix the configuration, or call listFolderTree to find the right folder and " + setterTool
						+ " to override it for this session.");
			}
			cache.put(account, configuredName);
			return configuredName;
		}

		var allFolders = store.getDefaultFolder().list("*");

		for (var folder : allFolders) {
			if (folder instanceof IMAPFolder imapFolder && hasAttribute(imapFolder, specialUse)) {
				cache.put(account, folder.getFullName());
				return folder.getFullName();
			}
		}

		for (var candidate : candidates) {
			try {
				if (store.getFolder(candidate).exists()) {
					cache.put(account, candidate);
					return candidate;
				}
			} catch (MessagingException e) {
				// Some servers reject a LIST for a name they consider malformed (e.g. the brackets in
				// [Gmail]/Trash). That only means this candidate is not one of their folders.
			}
		}

		for (var folder : allFolders) {
			var name = folder.getFullName();
			var sep = folder.getSeparator();
			var leaf = name.indexOf(sep) >= 0 ? name.substring(name.lastIndexOf(sep) + 1) : name;
			for (var leafName : leafNames) {
				if (leaf.equalsIgnoreCase(leafName)) {
					cache.put(account, name);
					return name;
				}
			}
		}
		return null;
	}

	private static boolean hasAttribute(IMAPFolder folder, String attribute) {
		try {
			for (var attr : folder.getAttributes()) {
				if (attr.equalsIgnoreCase(attribute)) {
					return true;
				}
			}
		} catch (MessagingException e) {
			// Attributes are optional metadata; a failure here just means we fall through to name matching.
		}
		return false;
	}

	// ── Spam folder detection ─────────────────────────────────────────────

	private static final List<String> SPAM_FOLDER_CANDIDATES = List.of("Spam", "Junk", "[Gmail]/Spam", "Junk E-mail",
			"Bulk Mail", "Junk Email");
	private static final List<String> SPAM_FOLDER_LEAF_NAMES = List.of("Spam", "Junk", "Junk E-mail", "Bulk Mail",
			"Junk Email");

	public String getSpamFolder(String account) throws MessagingException {
		return resolveSpecialFolder(account, cachedSpamFolders, getAccountConfig(account).spamFolder(), "spam",
				"setSpamFolder", "\\Junk", SPAM_FOLDER_CANDIDATES, SPAM_FOLDER_LEAF_NAMES);
	}

	public void setSpamFolder(String account, String folderName) {
		cachedSpamFolders.put(account, folderName);
	}

	// ── Trash folder detection ─────────────────────────────────────────

	private static final List<String> TRASH_FOLDER_CANDIDATES = List.of("Trash", "[Gmail]/Trash", "Deleted Items",
			"Deleted", "Deleted Messages", "INBOX.Trash");
	private static final List<String> TRASH_FOLDER_LEAF_NAMES = List.of("Trash", "Deleted Items", "Deleted",
			"Deleted Messages", "Papperskorg", "Papierkorb", "Corbeille");

	public String getTrashFolder(String account) throws MessagingException {
		return resolveSpecialFolder(account, cachedTrashFolders, getAccountConfig(account).trashFolder(), "trash",
				"setTrashFolder", "\\Trash", TRASH_FOLDER_CANDIDATES, TRASH_FOLDER_LEAF_NAMES);
	}

	public void setTrashFolder(String account, String folderName) {
		cachedTrashFolders.put(account, folderName);
	}

	public void moveToSpam(String account, String sourceFolderName, long uid, boolean markRead)
			throws MessagingException {
		moveToSpam(account, sourceFolderName, List.of(uid), markRead);
	}

	public int moveToSpam(String account, String sourceFolderName, List<Long> uids, boolean markRead)
			throws MessagingException {
		var spamFolder = getSpamFolder(account);
		if (spamFolder == null) {
			throw new MessagingException("No spam folder detected. Call listFolderTree to find it and setSpamFolder "
					+ "to configure it for this session, or set EMAIL_ACCOUNTS_<NAME>_SPAM_FOLDER.");
		}
		return moveEmails(account, sourceFolderName, uids, spamFolder, markRead);
	}

	// ── Drafts folder detection ────────────────────────────────────────

	private static final List<String> DRAFTS_FOLDER_CANDIDATES = List.of("Drafts", "[Gmail]/Drafts", "Draft", "DRAFT",
			"INBOX.Drafts", "INBOX.Draft");
	private static final List<String> DRAFTS_FOLDER_LEAF_NAMES = List.of("Drafts", "Draft");

	public String getDraftsFolder(String account) throws MessagingException {
		return resolveSpecialFolder(account, cachedDraftsFolders, getAccountConfig(account).draftsFolder(), "drafts",
				"setDraftsFolder", "\\Drafts", DRAFTS_FOLDER_CANDIDATES, DRAFTS_FOLDER_LEAF_NAMES);
	}

	public void setDraftsFolder(String account, String folderName) {
		cachedDraftsFolders.put(account, folderName);
	}

	public void saveDraft(
		String account, String to, String cc, String bcc, String subject, String body, String inReplyToFolder,
		long inReplyToUid) throws MessagingException {
		var ac = getAccountConfig(account);
		var session = Session.getInstance(new Properties());
		var draft = new MimeMessage(session);
		draft.setFrom(fromAddress(ac));
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
						var refs = (origRefs != null && origRefs.length > 0 ? origRefs[0] + " " : "") + messageId[0];
						draft.setHeader("References", refs);
					}
				}
			} finally {
				origFolder.close(false);
			}
		}

		var draftsName = getDraftsFolder(account);
		if (draftsName == null) {
			throw new MessagingException(
					"No Drafts folder detected. Call listFolderTree to find it and setDraftsFolder "
							+ "to configure it for this session, or set EMAIL_ACCOUNTS_<NAME>_DRAFTS_FOLDER.");
		}

		var store = getImapStore(account);
		var draftsFolder = store.getFolder(draftsName);
		draftsFolder.open(Folder.READ_WRITE);
		try {
			draft.setFlag(Flags.Flag.SEEN, true);
			draft.setFlag(Flags.Flag.DRAFT, true);
			draftsFolder.appendMessages(new Message[] {draft});
		} finally {
			draftsFolder.close(false);
		}
	}

	// ── List emails ──────────────────────────────────────────────────────

	public record EmailHeader(long uid, String subject, String from, String date, boolean seen, boolean answered,
			boolean forwarded, int size) {
	}

	public List<EmailHeader> listEmails(String account, String folderName, int offset, int limit, String sortBy)
			throws MessagingException {
		var store = getImapStore(account);
		var folder = store.getFolder(folderName);
		folder.open(Folder.READ_ONLY);
		try {
			var uf = (UIDFolder) folder;
			int total = folder.getMessageCount();
			if (total == 0)
				return List.of();

			boolean needsFullScan = "size".equalsIgnoreCase(sortBy) || "from".equalsIgnoreCase(sortBy);
			Message[] messages;
			if (needsFullScan) {
				messages = folder.getMessages(1, total);
			} else {
				int end = total - offset;
				int start = Math.max(1, end - limit + 1);
				if (end < 1)
					return List.of();
				messages = folder.getMessages(start, end);
			}

			var fp = new FetchProfile();
			fp.add(FetchProfile.Item.ENVELOPE);
			fp.add(FetchProfile.Item.FLAGS);
			fp.add(FetchProfile.Item.SIZE);
			fp.add(UIDFolder.FetchProfileItem.UID);
			folder.fetch(messages, fp);

			if ("size".equalsIgnoreCase(sortBy)) {
				Arrays.sort(messages, (a, b) -> {
					try {
						return Integer.compare(b.getSize(), a.getSize());
					} catch (Exception e) {
						return 0;
					}
				});
			} else if ("from".equalsIgnoreCase(sortBy)) {
				Arrays.sort(messages, (a, b) -> {
					try {
						var fa = a.getFrom() != null && a.getFrom().length > 0 ? a.getFrom()[0].toString() : "";
						var fb = b.getFrom() != null && b.getFrom().length > 0 ? b.getFrom()[0].toString() : "";
						return fa.compareToIgnoreCase(fb);
					} catch (Exception e) {
						return 0;
					}
				});
			}

			var result = new ArrayList<EmailHeader>();
			int fromIdx = needsFullScan ? offset : messages.length - 1;
			int step = needsFullScan ? 1 : -1;
			for (int i = fromIdx; result.size() < limit && i >= 0 && i < messages.length; i += step) {
				var m = messages[i];
				var from = m.getFrom() != null && m.getFrom().length > 0 ? m.getFrom()[0].toString() : "(unknown)";
				var date = m.getSentDate() != null ? m.getSentDate().toString() : "(no date)";
				boolean seen = m.isSet(Flags.Flag.SEEN);
				boolean answered = m.isSet(Flags.Flag.ANSWERED);
				boolean forwarded = isForwarded(m);
				result.add(new EmailHeader(uf.getUID(m), m.getSubject(), from, date, seen, answered, forwarded,
						m.getSize()));
			}
			return result;
		} finally {
			folder.close(false);
		}
	}

	// ── Read a single email ──────────────────────────────────────────────

	/**
	 * @param to
	 *            the To recipients, or null when the message has none (Bcc delivery, undisclosed
	 *            recipients)
	 * @param cc
	 *            the Cc recipients, or null when there are none
	 */
	public record EmailContent(String subject, String from, String to, String cc, String date, boolean seen,
			boolean answered, boolean forwarded, String body, boolean html, List<String> attachments, int size) {
	}

	/** The recipients of one type as a display string, or null when there are none. */
	private static String recipients(Message message, Message.RecipientType type) throws MessagingException {
		var addresses = message.getRecipients(type);
		return addresses != null && addresses.length > 0 ? InternetAddress.toString(addresses) : null;
	}

	public EmailContent readEmail(String account, String folderName, long uid) throws MessagingException, IOException {
		var store = getImapStore(account);
		var folder = store.getFolder(folderName);
		folder.open(Folder.READ_ONLY);
		try {
			var uf = (UIDFolder) folder;
			var message = uf.getMessageByUID(uid);
			if (message == null)
				throw new MessagingException("No message with UID " + uid);

			var from = message.getFrom() != null && message.getFrom().length > 0 ? message.getFrom()[0].toString()
					: "(unknown)";
			var to = recipients(message, Message.RecipientType.TO);
			var cc = recipients(message, Message.RecipientType.CC);
			var date = message.getSentDate() != null ? message.getSentDate().toString() : "(no date)";
			boolean seen = message.isSet(Flags.Flag.SEEN);

			var extracted = extractText(message);
			var body = extracted != null ? extracted.text() : null;
			boolean isHtml = extracted != null && extracted.html();
			var attachments = extractAttachmentNames(message);

			boolean answered = message.isSet(Flags.Flag.ANSWERED);
			boolean forwarded = isForwarded(message);
			return new EmailContent(message.getSubject(), from, to, cc, date, seen, answered, forwarded, body, isHtml,
					attachments, message.getSize());
		} finally {
			folder.close(false);
		}
	}

	private record ExtractedText(String text, boolean html) {
	}

	private ExtractedText extractText(Part part) throws MessagingException, IOException {
		if (part.isMimeType("text/plain")) {
			return new ExtractedText((String) part.getContent(), false);
		}
		if (part.isMimeType("text/html")) {
			return new ExtractedText((String) part.getContent(), true);
		}
		if (part.isMimeType("message/rfc822")) {
			return extractText((Part) part.getContent());
		}
		if (part.isMimeType("multipart/alternative")) {
			var mp = (Multipart) part.getContent();
			ExtractedText fallback = null;
			for (int i = 0; i < mp.getCount(); i++) {
				var bp = mp.getBodyPart(i);
				var extracted = extractText(bp);
				if (extracted != null) {
					if (bp.isMimeType("text/html"))
						return extracted;
					if (fallback == null)
						fallback = extracted;
				}
			}
			return fallback;
		}
		if (part.isMimeType("multipart/*")) {
			var mp = (Multipart) part.getContent();
			var parts = new ArrayList<String>();
			boolean anyHtml = false;
			for (int i = 0; i < mp.getCount(); i++) {
				var extracted = extractText(mp.getBodyPart(i));
				if (extracted != null) {
					parts.add(extracted.text());
					if (extracted.html())
						anyHtml = true;
				}
			}
			return parts.isEmpty() ? null : new ExtractedText(String.join("\n\n", parts), anyHtml);
		}
		return null;
	}

	public record AttachmentContent(String fileName, String mimeType, byte[] data) {
	}

	public AttachmentContent getAttachment(String account, String folderName, long uid, String attachmentName)
			throws MessagingException, IOException {
		var store = getImapStore(account);
		var folder = store.getFolder(folderName);
		folder.open(Folder.READ_ONLY);
		try {
			var uf = (UIDFolder) folder;
			var message = uf.getMessageByUID(uid);
			if (message == null)
				throw new MessagingException("No message with UID " + uid);

			var part = findAttachment(message, attachmentName);
			if (part == null) {
				throw new MessagingException("Attachment not found: " + attachmentName
						+ ". Use readEmail to see available attachment names.");
			}

			var data = part.getInputStream().readAllBytes();
			var mimeType = part.getContentType();
			if (mimeType != null && mimeType.contains(";")) {
				mimeType = mimeType.substring(0, mimeType.indexOf(';')).trim();
			}
			// Some servers report the type in upper case in BODYSTRUCTURE; media types are case-insensitive
			// and clients expect the canonical lower-case form.
			return new AttachmentContent(attachmentName,
					mimeType != null ? mimeType.toLowerCase(Locale.ROOT) : "application/octet-stream", data);
		} finally {
			folder.close(false);
		}
	}

	/**
	 * A part is an attachment when it carries a file name, whatever its Content-Disposition says.
	 * Apple Mail sends attachments as {@code inline}, and some clients send no disposition at all,
	 * so keying on {@code Content-Disposition: attachment} alone hides real attachments. The name
	 * comes from the disposition's {@code filename} or, failing that, the content type's
	 * {@code name}.
	 * <p>
	 * Non-ASCII names arrive in one of two encodings. RFC 2231 ({@code filename*=UTF-8''...}, used
	 * by Apple Mail and Thunderbird) is decoded by Jakarta Mail itself. RFC 2047 encoded-words
	 * inside the value ({@code =?UTF-8?Q?...?=}, used by Outlook and Gmail) are decoded here, since
	 * Jakarta Mail only does that behind the {@code mail.mime.decodefilename} system property,
	 * which is read in a static initializer and so cannot be relied on in a native image.
	 */
	static String attachmentName(Part part) throws MessagingException {
		return decodeAttachmentName(part.getFileName());
	}

	static String decodeAttachmentName(String rawName) {
		if (rawName == null || rawName.isBlank()) {
			return null;
		}
		try {
			return MimeUtility.decodeText(rawName);
		} catch (UnsupportedEncodingException e) {
			// An encoded-word in a charset this JVM lacks: the raw form is still a usable identifier.
			return rawName;
		}
	}

	private Part findAttachment(Part part, String name) throws MessagingException, IOException {
		if (part.isMimeType("multipart/*")) {
			var mp = (Multipart) part.getContent();
			for (int i = 0; i < mp.getCount(); i++) {
				var bp = mp.getBodyPart(i);
				if (name.equals(attachmentName(bp))) {
					return bp;
				}
				var found = findAttachment(bp, name);
				if (found != null)
					return found;
			}
		} else if (part.isMimeType("message/rfc822")) {
			return findAttachment((Part) part.getContent(), name);
		}
		return null;
	}

	private List<String> extractAttachmentNames(Part part) throws MessagingException, IOException {
		var names = new ArrayList<String>();
		if (part.isMimeType("multipart/*")) {
			var mp = (Multipart) part.getContent();
			for (int i = 0; i < mp.getCount(); i++) {
				var bp = mp.getBodyPart(i);
				var name = attachmentName(bp);
				if (name != null) {
					names.add(name);
				}
				names.addAll(extractAttachmentNames(bp));
			}
		} else if (part.isMimeType("message/rfc822")) {
			names.addAll(extractAttachmentNames((Part) part.getContent()));
		}
		return names;
	}

	// ── Removing messages from a folder without collateral damage ────────

	/**
	 * A plain IMAP EXPUNGE removes <em>every</em> message in the folder that carries the \Deleted
	 * flag, not only the ones this server flagged. Mail clients such as Thunderbird and Outlook can
	 * leave messages flagged \Deleted but not yet purged, and a naive move would silently destroy
	 * them. So messages are removed in this order of preference:
	 * <ol>
	 * <li>the MOVE extension (RFC 6851): one command, nothing is flagged and nothing is
	 * expunged,</li>
	 * <li>COPY, flag \Deleted, then UID EXPUNGE (RFC 4315, UIDPLUS) of exactly those UIDs,</li>
	 * <li>COPY, flag \Deleted and a plain EXPUNGE, but only after {@link #checkExpungeIsSafe} has
	 * verified that nothing else in the folder is flagged \Deleted.</li>
	 * </ol>
	 */
	private static boolean hasCapability(Store store, String capability) throws MessagingException {
		return store instanceof IMAPStore imapStore && imapStore.hasCapability(capability);
	}

	/**
	 * Call before touching anything when the fallback to a plain EXPUNGE may be needed. Throws if
	 * that fallback would remove messages that another client flagged \Deleted.
	 *
	 * @param moving
	 *            true when the messages are moved to another folder (MOVE avoids the expunge
	 *            altogether), false when they are only removed
	 */
	private static void checkExpungeIsSafe(Store store, Folder openSource, boolean moving) throws MessagingException {
		if (hasCapability(store, "UIDPLUS") || (moving && hasCapability(store, "MOVE"))) {
			return;
		}
		var alreadyDeleted = openSource.search(new FlagTerm(new Flags(Flags.Flag.DELETED), true));
		if (alreadyDeleted.length > 0) {
			throw new MessagingException("Refusing to " + (moving ? "move" : "delete") + " messages in "
					+ openSource.getFullName() + ": the server supports neither MOVE nor UIDPLUS, so the removal "
					+ "would need a plain EXPUNGE, and " + alreadyDeleted.length
					+ " other message(s) in the folder are flagged \\Deleted by another mail client and would be "
					+ "purged as well. Purge or undelete them in that client first, then retry.");
		}
	}

	/**
	 * Moves the messages out of the open source folder; see {@link #hasCapability} for the
	 * strategy.
	 */
	private static void moveMessages(Store store, Folder openSource, Message[] messages, Folder target)
			throws MessagingException {
		if (hasCapability(store, "MOVE") && openSource instanceof IMAPFolder imapFolder) {
			imapFolder.moveMessages(messages, target);
			return;
		}
		openSource.copyMessages(messages, target);
		removeMessages(store, openSource, messages);
	}

	/** Flags the messages \Deleted and expunges them, and only them where the server allows it. */
	private static void removeMessages(Store store, Folder openSource, Message[] messages) throws MessagingException {
		for (var m : messages) {
			m.setFlag(Flags.Flag.DELETED, true);
		}
		if (hasCapability(store, "UIDPLUS") && openSource instanceof IMAPFolder imapFolder) {
			imapFolder.expunge(messages);
		} else {
			openSource.expunge();
		}
	}

	// ── Move emails ─────────────────────────────────────────────────────

	public void moveEmail(String account, String sourceFolderName, long uid, String targetFolderName, boolean markRead)
			throws MessagingException {
		moveEmails(account, sourceFolderName, List.of(uid), targetFolderName, markRead);
	}

	public int moveEmails(
		String account, String sourceFolderName, List<Long> uids, String targetFolderName, boolean markRead)
			throws MessagingException {
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
			if (messages.isEmpty())
				return 0;
			checkExpungeIsSafe(store, sourceFolder, true);

			var msgArray = messages.toArray(new Message[0]);
			if (markRead) {
				for (var m : msgArray) {
					m.setFlag(Flags.Flag.SEEN, true);
				}
			}
			moveMessages(store, sourceFolder, msgArray, targetFolder);
			return msgArray.length;
		} finally {
			sourceFolder.close(false);
		}
	}

	public record BatchMoveResult(int totalMoved, Map<String, Integer> perFolder) {
	}

	public BatchMoveResult batchMoveEmails(
		String account, String sourceFolderName, Map<String, List<Long>> targetToUids, boolean markRead)
			throws MessagingException {
		var store = getImapStore(account);
		var sourceFolder = store.getFolder(sourceFolderName);
		sourceFolder.open(Folder.READ_WRITE);
		try {
			var uf = (UIDFolder) sourceFolder;
			int totalMoved = 0;
			var perFolder = new LinkedHashMap<String, Integer>();
			checkExpungeIsSafe(store, sourceFolder, true);

			for (var entry : targetToUids.entrySet()) {
				var targetFolder = store.getFolder(entry.getKey());
				var messages = new ArrayList<Message>();
				for (long uid : entry.getValue()) {
					var m = uf.getMessageByUID(uid);
					if (m != null)
						messages.add(m);
				}
				if (messages.isEmpty())
					continue;

				var msgArray = messages.toArray(new Message[0]);
				if (markRead) {
					for (var m : msgArray) {
						m.setFlag(Flags.Flag.SEEN, true);
					}
				}
				moveMessages(store, sourceFolder, msgArray, targetFolder);
				totalMoved += msgArray.length;
				perFolder.put(entry.getKey(), msgArray.length);
			}

			return new BatchMoveResult(totalMoved, perFolder);
		} finally {
			sourceFolder.close(false);
		}
	}

	// ── Delete email (move to Trash) ─────────────────────────────────────

	/**
	 * Moves the message to the account's trash folder (see {@link #getTrashFolder}); when no trash
	 * folder can be resolved, removes it outright. A configured trash folder that does not exist is
	 * an error, not a fall-through to permanent removal.
	 */
	public void deleteEmail(String account, String folderName, long uid) throws MessagingException {
		var store = getImapStore(account);
		var trashName = getTrashFolder(account);
		var trashFolder = trashName != null && !trashName.equals(folderName) ? store.getFolder(trashName) : null;

		var sourceFolder = store.getFolder(folderName);
		sourceFolder.open(Folder.READ_WRITE);
		try {
			var uf = (UIDFolder) sourceFolder;
			var message = uf.getMessageByUID(uid);
			if (message == null)
				throw new MessagingException("No message with UID " + uid);

			var messages = new Message[] {message};
			if (trashFolder != null) {
				checkExpungeIsSafe(store, sourceFolder, true);
				moveMessages(store, sourceFolder, messages, trashFolder);
			} else {
				checkExpungeIsSafe(store, sourceFolder, false);
				removeMessages(store, sourceFolder, messages);
			}
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
			SearchTerm term = new OrTerm(
					new SearchTerm[] {new SubjectTerm(query), new FromStringTerm(query), new BodyTerm(query)});
			var messages = folder.search(term);

			var fp = new FetchProfile();
			fp.add(FetchProfile.Item.ENVELOPE);
			fp.add(FetchProfile.Item.FLAGS);
			fp.add(FetchProfile.Item.SIZE);
			fp.add(UIDFolder.FetchProfileItem.UID);
			folder.fetch(messages, fp);

			var result = new ArrayList<EmailHeader>();
			int count = Math.min(messages.length, limit > 0 ? limit : 20);
			for (int i = messages.length - 1; i >= 0 && result.size() < count; i--) {
				var m = messages[i];
				var from = m.getFrom() != null && m.getFrom().length > 0 ? m.getFrom()[0].toString() : "(unknown)";
				var date = m.getSentDate() != null ? m.getSentDate().toString() : "(no date)";
				boolean seen = m.isSet(Flags.Flag.SEEN);
				boolean answered = m.isSet(Flags.Flag.ANSWERED);
				boolean forwarded = isForwarded(m);
				result.add(new EmailHeader(uf.getUID(m), m.getSubject(), from, date, seen, answered, forwarded,
						m.getSize()));
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
		if (smtpSsl(smtpCfg)) {
			// Implicit TLS (port 465): encrypted from the first byte, nothing to upgrade.
			props.put("mail.smtp.ssl.enable", "true");
		} else if (smtpCfg.starttls()) {
			// Plain connection upgraded with STARTTLS (port 587). Required, not opportunistic: without
			// "required" Jakarta Mail would quietly fall back to sending the password in the clear if
			// the server did not advertise STARTTLS.
			props.put("mail.smtp.starttls.enable", "true");
			props.put("mail.smtp.starttls.required", "true");
		}
		applyNetworkTimeout(props, "smtp");

		var session = Session.getInstance(props, new Authenticator() {
			@Override
			protected PasswordAuthentication getPasswordAuthentication() {
				return new PasswordAuthentication(smtpCfg.username(), smtpCfg.password());
			}
		});

		var message = new MimeMessage(session);
		message.setFrom(fromAddress(ac));
		return message;
	}

	public void sendEmail(String account, String to, String cc, String bcc, String subject, String body)
			throws MessagingException {
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

	public void replyEmail(
		String account, String folderName, long uid, String body, boolean replyAll, String extraCc, String extraBcc)
			throws MessagingException {
		var ac = getAccountConfig(account);
		var store = getImapStore(account);
		var folder = store.getFolder(folderName);
		folder.open(Folder.READ_ONLY);
		try {
			var uf = (UIDFolder) folder;
			var original = uf.getMessageByUID(uid);
			if (original == null)
				throw new MessagingException("No message with UID " + uid);

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
			if (subject == null)
				subject = "";
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

			// Reply-All: add original To and Cc as Cc, excluding our own addresses
			if (replyAll) {
				var own = ownAddresses(ac);
				var ccList = new ArrayList<Address>();
				for (var type : List.of(Message.RecipientType.TO, Message.RecipientType.CC)) {
					var recipients = original.getRecipients(type);
					if (recipients == null) {
						continue;
					}
					for (var addr : recipients) {
						if (!isOwnAddress(addr, own)) {
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
			if (original == null)
				throw new MessagingException("No message with UID " + uid);

			var forward = buildSmtpMessage(account);

			var subject = original.getSubject();
			if (subject == null)
				subject = "";
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

	private static final List<String> TRIAGE_HEADERS = List.of("From", "Reply-To", "Return-Path", "To", "Cc", "Subject",
			"Date", "List-Unsubscribe", "X-Spam-Status", "X-Spam-Flag", "X-Spam-Score", "Authentication-Results",
			"X-Mailer", "X-Priority");

	public record EmailSummary(long uid, boolean answered, boolean forwarded, Map<String, String> headers, int size) {
	}

	/**
	 * @param spamScore
	 *            the filter's score, or null when no header carried one
	 * @param spamFlagged
	 *            the filter's verdict ({@code X-Spam-Flag: YES} or {@code X-Spam-Status: Yes}),
	 *            which is reported separately so a verdict shows even when the score format is
	 *            unknown
	 */
	public record CompactEmailSummary(long uid, String from, String subject, String date, boolean answered,
			boolean forwarded, Double spamScore, boolean spamFlagged, boolean hasListUnsubscribe,
			boolean fromReplyToMismatch, int size) {
	}

	private static final java.util.regex.Pattern LEADING_NUMBER = java.util.regex.Pattern
			.compile("^\\s*(?:score=|hits=)?([-+]?\\d+(?:\\.\\d+)?)");
	private static final java.util.regex.Pattern STATUS_SCORE = java.util.regex.Pattern
			.compile("(?:score|hits)=([-+]?\\d+(?:\\.\\d+)?)");

	/**
	 * The spam score from the headers filters commonly set. {@code X-Spam-Score} is rarely a bare
	 * number: Amavis writes {@code 3.2 (***)}, some rspamd and Exim setups {@code 3.21 / 5.0}, so
	 * only the leading number is taken. {@code X-Spam-Status} carries {@code score=} (SpamAssassin)
	 * or {@code hits=} (older installs).
	 */
	static Double parseSpamScore(String xSpamScore, String xSpamStatus) {
		if (xSpamScore != null) {
			var m = LEADING_NUMBER.matcher(xSpamScore);
			if (m.find()) {
				return Double.valueOf(m.group(1));
			}
		}
		if (xSpamStatus != null) {
			var m = STATUS_SCORE.matcher(xSpamStatus);
			if (m.find()) {
				return Double.valueOf(m.group(1));
			}
		}
		return null;
	}

	/**
	 * The filter's verdict: {@code X-Spam-Flag: YES}, or an {@code X-Spam-Status} starting with
	 * Yes.
	 */
	static boolean parseSpamFlag(String xSpamFlag, String xSpamStatus) {
		if (xSpamFlag != null && xSpamFlag.trim().equalsIgnoreCase("YES")) {
			return true;
		}
		return xSpamStatus != null && xSpamStatus.trim().regionMatches(true, 0, "Yes", 0, 3);
	}

	private static String firstHeader(Message m, String name) throws MessagingException {
		var values = m.getHeader(name);
		return values != null && values.length > 0 ? values[0] : null;
	}

	public List<CompactEmailSummary> summarizeEmailsCompact(
		String account, String folderName, boolean unreadOnly, int offset, int limit) throws MessagingException {
		var store = getImapStore(account);
		var folder = store.getFolder(folderName);
		folder.open(Folder.READ_ONLY);
		try {
			var uf = (UIDFolder) folder;
			if (limit <= 0)
				limit = 20;

			Message[] messages;
			if (unreadOnly) {
				messages = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
				if (messages.length == 0)
					return List.of();
				var fp = new FetchProfile();
				fp.add(FetchProfile.Item.ENVELOPE);
				fp.add(FetchProfile.Item.FLAGS);
				fp.add(FetchProfile.Item.SIZE);
				fp.add(UIDFolder.FetchProfileItem.UID);
				folder.fetch(messages, fp);
				Arrays.sort(messages, Comparator.comparingLong(m -> {
					try {
						return uf.getUID(m);
					} catch (Exception e) {
						return 0;
					}
				}));
			} else {
				int total = folder.getMessageCount();
				if (total == 0)
					return List.of();
				int end = total - offset;
				int start = Math.max(1, end - limit + 1);
				if (end < 1)
					return List.of();
				messages = folder.getMessages(start, end);
				var fp = new FetchProfile();
				fp.add(FetchProfile.Item.ENVELOPE);
				fp.add(FetchProfile.Item.FLAGS);
				fp.add(FetchProfile.Item.SIZE);
				fp.add(UIDFolder.FetchProfileItem.UID);
				folder.fetch(messages, fp);
			}

			int count = Math.min(messages.length, limit);
			var result = new ArrayList<CompactEmailSummary>(count);
			for (int i = unreadOnly ? 0 : messages.length - 1; unreadOnly ? i < count
					: i >= 0 && result.size() < count; i += unreadOnly ? 1 : -1) {
				var m = messages[i];

				var fromAddrs = m.getFrom();
				var from = (fromAddrs != null && fromAddrs.length > 0) ? fromAddrs[0].toString() : "(unknown)";
				var subject = m.getSubject() != null ? m.getSubject() : "(no subject)";
				var date = m.getSentDate() != null ? m.getSentDate().toString() : "(no date)";

				var spamStatus = firstHeader(m, "X-Spam-Status");
				var spamScore = parseSpamScore(firstHeader(m, "X-Spam-Score"), spamStatus);
				var spamFlagged = parseSpamFlag(firstHeader(m, "X-Spam-Flag"), spamStatus);

				var listUnsub = m.getHeader("List-Unsubscribe");
				boolean hasListUnsubscribe = listUnsub != null && listUnsub.length > 0;

				boolean fromReplyToMismatch = false;
				var replyTo = m.getReplyTo();
				if (replyTo != null && replyTo.length > 0 && fromAddrs != null && fromAddrs.length > 0) {
					var fromStr = fromAddrs[0] instanceof InternetAddress ia ? ia.getAddress()
							: fromAddrs[0].toString();
					var replyStr = replyTo[0] instanceof InternetAddress ia ? ia.getAddress() : replyTo[0].toString();
					if (fromStr != null && replyStr != null) {
						fromReplyToMismatch = !fromStr.equalsIgnoreCase(replyStr);
					}
				}

				boolean answered = m.isSet(Flags.Flag.ANSWERED);
				boolean forwarded = isForwarded(m);
				result.add(new CompactEmailSummary(uf.getUID(m), from, subject, date, answered, forwarded, spamScore,
						spamFlagged, hasListUnsubscribe, fromReplyToMismatch, m.getSize()));
			}
			return result;
		} finally {
			folder.close(false);
		}
	}

	public List<EmailSummary> summarizeEmails(
		String account, String folderName, boolean unreadOnly, int offset, int limit) throws MessagingException {
		var store = getImapStore(account);
		var folder = store.getFolder(folderName);
		folder.open(Folder.READ_ONLY);
		try {
			var uf = (UIDFolder) folder;
			if (limit <= 0)
				limit = 20;

			Message[] messages;
			if (unreadOnly) {
				messages = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
				if (messages.length == 0)
					return List.of();
				var fp = new FetchProfile();
				fp.add(FetchProfile.Item.FLAGS);
				fp.add(FetchProfile.Item.SIZE);
				fp.add(UIDFolder.FetchProfileItem.UID);
				folder.fetch(messages, fp);
				Arrays.sort(messages, Comparator.comparingLong(m -> {
					try {
						return uf.getUID(m);
					} catch (Exception e) {
						return 0;
					}
				}));
			} else {
				int total = folder.getMessageCount();
				if (total == 0)
					return List.of();
				int end = total - offset;
				int start = Math.max(1, end - limit + 1);
				if (end < 1)
					return List.of();
				messages = folder.getMessages(start, end);
				var fp = new FetchProfile();
				fp.add(FetchProfile.Item.FLAGS);
				fp.add(FetchProfile.Item.SIZE);
				fp.add(UIDFolder.FetchProfileItem.UID);
				folder.fetch(messages, fp);
			}

			int count = Math.min(messages.length, limit);
			var result = new ArrayList<EmailSummary>(count);
			for (int i = unreadOnly ? 0 : messages.length - 1; unreadOnly ? i < count
					: i >= 0 && result.size() < count; i += unreadOnly ? 1 : -1) {
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
				result.add(new EmailSummary(uf.getUID(m), answered, forwarded, headers, m.getSize()));
			}
			return result;
		} finally {
			folder.close(false);
		}
	}

	public record FullEmail(long uid, int unreadLeft, boolean answered, boolean forwarded, Map<String, String> headers,
			String body, boolean html, List<String> attachments, int size) {
	}

	public FullEmail getNextUnreadEmail(String account, String folderName) throws MessagingException, IOException {
		var store = getImapStore(account);
		var folder = store.getFolder(folderName);
		folder.open(Folder.READ_ONLY);
		try {
			var uf = (UIDFolder) folder;
			var unreadTerm = new FlagTerm(new Flags(Flags.Flag.SEEN), false);
			var messages = folder.search(unreadTerm);
			if (messages.length == 0)
				return null;

			var fp = new FetchProfile();
			fp.add(FetchProfile.Item.FLAGS);
			fp.add(UIDFolder.FetchProfileItem.UID);
			folder.fetch(messages, fp);

			Arrays.sort(messages, Comparator.comparingLong(m -> {
				try {
					return uf.getUID(m);
				} catch (Exception e) {
					return 0;
				}
			}));

			var message = messages[0];

			var headers = new LinkedHashMap<String, String>();
			var allHeaders = message.getAllHeaders();
			while (allHeaders.hasMoreElements()) {
				var h = allHeaders.nextElement();
				headers.merge(h.getName(), h.getValue(), (old, val) -> old + "\n" + val);
			}

			var extracted = extractText(message);
			var body = extracted != null ? extracted.text() : null;
			boolean isHtml = extracted != null && extracted.html();
			var attachments = extractAttachmentNames(message);

			boolean answered = message.isSet(Flags.Flag.ANSWERED);
			boolean forwarded = isForwarded(message);
			return new FullEmail(uf.getUID(message), messages.length, answered, forwarded, headers, body, isHtml,
					attachments, message.getSize());
		} finally {
			folder.close(false);
		}
	}

	// ── Set flags on one or more emails ─────────────────────────────────

	public int setMessageFlags(
		String account, String folderName, List<Long> uids, Boolean seen, Boolean answered, Boolean forwarded,
		Boolean flagged) throws MessagingException {
		var store = getImapStore(account);
		var folder = store.getFolder(folderName);
		folder.open(Folder.READ_WRITE);
		try {
			var uf = (UIDFolder) folder;
			int count = 0;
			for (long uid : uids) {
				var m = uf.getMessageByUID(uid);
				if (m == null)
					continue;
				if (seen != null)
					m.setFlag(Flags.Flag.SEEN, seen);
				if (answered != null)
					m.setFlag(Flags.Flag.ANSWERED, answered);
				if (forwarded != null)
					m.setFlags(new Flags("$Forwarded"), forwarded);
				if (flagged != null)
					m.setFlag(Flags.Flag.FLAGGED, flagged);
				count++;
			}
			return count;
		} finally {
			folder.close(false);
		}
	}
}
