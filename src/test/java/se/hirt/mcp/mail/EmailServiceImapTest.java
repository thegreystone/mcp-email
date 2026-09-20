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

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.activation.DataHandler;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.util.ByteArrayDataSource;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@link EmailService} against in-memory GreenMail IMAP and SMTP servers. The move and delete
 * tests reproduce the situation where another mail client has flagged a message \Deleted without
 * expunging it: the service must never purge that message as a side effect of its own work.
 */
class EmailServiceImapTest {

	private static final String USER = "user@localhost";
	private static final String PASSWORD = "secret";
	private static final String ACCOUNT = "test";

	@RegisterExtension
	static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
			.withConfiguration(GreenMailConfiguration.aConfig().withUser(USER, USER, PASSWORD));

	private EmailService service;

	@BeforeEach
	void setUp() throws Exception {
		service = new EmailService();
		service.config = config();
		var user = greenMail.setUser(USER, USER, PASSWORD);
		for (var subject : List.of("A", "B", "C")) {
			user.deliver(GreenMailUtil.createTextEmail(USER, "sender@example.com", subject, "body " + subject,
					ServerSetupTest.IMAP));
		}
		service.createFolder(ACCOUNT, "Archive");
	}

	@Test
	void moveLeavesOtherClientsDeletedFlagsAlone() throws Exception {
		flagDeletedFromAnotherClient("B");
		long uidC = uidOf("C");

		int moved = service.moveEmails(ACCOUNT, "INBOX", List.of(uidC), "Archive", false);

		assertEquals(1, moved);
		assertEquals(List.of("A", "B"), subjectsIn("INBOX"),
				"B must survive the move even though it is flagged \\Deleted");
		assertEquals(List.of("C"), subjectsIn("Archive"));
		assertTrue(isFlaggedDeleted("INBOX", "B"), "the other client's \\Deleted flag on B is still there");
	}

	@Test
	void batchMoveLeavesOtherClientsDeletedFlagsAlone() throws Exception {
		flagDeletedFromAnotherClient("B");
		service.createFolder(ACCOUNT, "Other");

		var result = service.batchMoveEmails(ACCOUNT, "INBOX",
				Map.of("Archive", List.of(uidOf("A")), "Other", List.of(uidOf("C"))), true);

		assertEquals(2, result.totalMoved());
		assertEquals(List.of("B"), subjectsIn("INBOX"));
		assertEquals(List.of("A"), subjectsIn("Archive"));
		assertEquals(List.of("C"), subjectsIn("Other"));
	}

	@Test
	void deleteWithoutTrashRemovesOnlyTheRequestedMessage() throws Exception {
		flagDeletedFromAnotherClient("B");

		service.deleteEmail(ACCOUNT, "INBOX", uidOf("A"));

		assertEquals(List.of("B", "C"), subjectsIn("INBOX"));
	}

	@Test
	void deleteMovesToTheResolvedTrashFolder() throws Exception {
		// No special-use flags on this server, so the trash folder is found by its well-known name.
		service.createFolder(ACCOUNT, "Trash");

		assertEquals("Trash", service.getTrashFolder(ACCOUNT));
		service.deleteEmail(ACCOUNT, "INBOX", uidOf("A"));

		assertEquals(List.of("B", "C"), subjectsIn("INBOX"));
		assertEquals(List.of("A"), subjectsIn("Trash"), "deleted message should be in Trash, not gone");
	}

	@Test
	void deleteFindsTrashUnderANamespacePrefix() throws Exception {
		// The OVH-style layout: everything nested under INBOX, so only the leaf name matches.
		service.createFolder(ACCOUNT, "INBOX.Papperskorg");

		assertEquals("INBOX.Papperskorg", service.getTrashFolder(ACCOUNT));
	}

	@Test
	void moveWorksWhenNothingElseIsFlagged() throws Exception {
		service.moveEmail(ACCOUNT, "INBOX", uidOf("A"), "Archive", true);

		assertEquals(List.of("B", "C"), subjectsIn("INBOX"));
		assertEquals(List.of("A"), subjectsIn("Archive"));
	}

	@Test
	void serverAdvertisesAtLeastOneSafeRemovalMechanism() throws Exception {
		// Documents which code path the tests above exercise. GreenMail advertises what it implements;
		// a server with neither capability takes the guarded plain-EXPUNGE fallback instead.
		try (var store = otherClient()) {
			var imap = (IMAPStore) store;
			System.out.println("GreenMail capabilities: MOVE=" + imap.hasCapability("MOVE") + ", UIDPLUS="
					+ imap.hasCapability("UIDPLUS"));
			assertTrue(imap.hasCapability("MOVE") || imap.hasCapability("UIDPLUS"),
					"the tests above are meant to run through MOVE or UID EXPUNGE");
		}
	}

	@Test
	void networkTimeoutFiresWhenServerStaysSilent() throws Exception {
		// A listening socket that is never accepted: the TCP handshake completes, the IMAP greeting never
		// comes. Without a timeout the connect would wait forever.
		try (var silentServer = new ServerSocket(0)) {
			var silentService = new EmailService();
			silentService.config = config(silentServer.getLocalPort(), ServerSetupTest.SMTP.getPort(), false, false, 1);

			long start = System.nanoTime();
			var e = assertThrows(MessagingException.class, () -> silentService.listFolders(ACCOUNT));
			long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

			assertTrue(elapsedMillis < 10_000,
					"expected to give up after about 1 s, took " + elapsedMillis + " ms: " + e.getMessage());
		}
	}

	@Test
	void attachmentsAreFoundWhateverTheDispositionSays() throws Exception {
		var pdfBytes = "%PDF-1.4 not really".getBytes(StandardCharsets.UTF_8);
		var binBytes = new byte[] {1, 2, 3, 4};

		var body = new MimeBodyPart();
		body.setText("See attached.");
		// Apple Mail style: a real attachment declared as inline.
		var inlinePdf = new MimeBodyPart();
		inlinePdf.setDataHandler(new DataHandler(new ByteArrayDataSource(pdfBytes, "application/pdf")));
		inlinePdf.setFileName("report.pdf");
		inlinePdf.setDisposition(Part.INLINE);
		// No Content-Disposition at all; only the content type's name parameter carries the file name.
		var nameOnly = new MimeBodyPart();
		nameOnly.setDataHandler(new DataHandler(new ByteArrayDataSource(binBytes, "application/octet-stream")));
		nameOnly.setHeader("Content-Type", "application/octet-stream; name=\"data.bin\"");
		// The classic form, to make sure it still works.
		var classic = new MimeBodyPart();
		classic.setDataHandler(new DataHandler(new ByteArrayDataSource(binBytes, "application/octet-stream")));
		classic.setFileName("classic.bin");
		classic.setDisposition(Part.ATTACHMENT);

		var multipart = new MimeMultipart();
		for (var part : List.of(body, inlinePdf, nameOnly, classic)) {
			multipart.addBodyPart(part);
		}
		var message = new MimeMessage(Session.getInstance(new Properties()));
		message.setFrom("sender@example.com");
		message.setRecipients(Message.RecipientType.TO, USER);
		message.setSubject("With attachments");
		message.setContent(multipart);
		message.saveChanges();
		greenMail.setUser(USER, USER, PASSWORD).deliver(message);

		var email = service.readEmail(ACCOUNT, "INBOX", uidOf("With attachments"));
		assertEquals(List.of("report.pdf", "data.bin", "classic.bin"), email.attachments());

		var fetched = service.getAttachment(ACCOUNT, "INBOX", uidOf("With attachments"), "report.pdf");
		assertArrayEquals(pdfBytes, fetched.data());
		assertEquals("application/pdf", fetched.mimeType());
		assertArrayEquals(binBytes,
				service.getAttachment(ACCOUNT, "INBOX", uidOf("With attachments"), "data.bin").data());
	}

	@Test
	void nonAsciiAttachmentNamesAreDecodedInBothEncodings() throws Exception {
		var bytes = new byte[] {9, 8, 7};

		var body = new MimeBodyPart();
		body.setText("Swedish file names.");
		// RFC 2047 encoded-word inside the filename value, as Outlook and Gmail send it.
		var encodedWord = new MimeBodyPart();
		encodedWord.setDataHandler(new DataHandler(new ByteArrayDataSource(bytes, "application/pdf")));
		encodedWord.setHeader("Content-Disposition",
				"attachment; filename=\"=?UTF-8?Q?Faktura_f=C3=B6r_=C3=A5ret.pdf?=\"");
		// RFC 2231 parameter encoding, as Apple Mail and Thunderbird send it.
		var rfc2231 = new MimeBodyPart();
		rfc2231.setDataHandler(new DataHandler(new ByteArrayDataSource(bytes, "application/pdf")));
		rfc2231.setHeader("Content-Disposition", "attachment; filename*=UTF-8''Bokf%C3%B6ring.pdf");

		var multipart = new MimeMultipart();
		for (var part : List.of(body, encodedWord, rfc2231)) {
			multipart.addBodyPart(part);
		}
		var message = new MimeMessage(Session.getInstance(new Properties()));
		message.setFrom("sender@example.com");
		message.setRecipients(Message.RecipientType.TO, USER);
		message.setSubject("Swedish names");
		message.setContent(multipart);
		message.saveChanges();
		greenMail.setUser(USER, USER, PASSWORD).deliver(message);

		long uid = uidOf("Swedish names");
		assertEquals(List.of("Faktura för året.pdf", "Bokföring.pdf"),
				service.readEmail(ACCOUNT, "INBOX", uid).attachments());
		// The decoded name, as shown to the model, is what getAttachment accepts.
		assertArrayEquals(bytes, service.getAttachment(ACCOUNT, "INBOX", uid, "Faktura för året.pdf").data());
		assertArrayEquals(bytes, service.getAttachment(ACCOUNT, "INBOX", uid, "Bokföring.pdf").data());
	}

	@Test
	void nextUnreadEmailRendersHtmlAsMarkdown() throws Exception {
		// Mark the plain-text fixtures read so the HTML message is the oldest unread one.
		service.setMessageFlags(ACCOUNT, "INBOX", List.of(uidOf("A"), uidOf("B"), uidOf("C")), true, null, null, null);
		var message = new MimeMessage(Session.getInstance(new Properties()));
		message.setFrom("sender@example.com");
		message.setRecipients(Message.RecipientType.TO, USER);
		message.setSubject("Newsletter");
		message.setContent(
				"<html><body><p>Hello <b>reader</b>,</p>"
						+ "<p>See <a href=\"https://example.com/offer\">the offer</a>.</p></body></html>",
				"text/html; charset=UTF-8");
		message.saveChanges();
		greenMail.setUser(USER, USER, PASSWORD).deliver(message);

		var tools = new EmailTools();
		tools.emailService = service;
		var rendered = tools.getNextUnreadEmail(ACCOUNT, "INBOX");

		assertTrue(rendered.contains("Subject: Newsletter"), rendered);
		assertTrue(rendered.contains("Hello **reader**,"), "HTML should be rendered as markdown:\n" + rendered);
		assertTrue(rendered.contains("[the offer](https://example.com/offer)"), rendered);
		assertFalse(rendered.contains("<p>") || rendered.contains("<html"), "no raw HTML tags:\n" + rendered);
	}

	@Test
	void compactTriageShowsAmavisStyleScoreAndVerdict() throws Exception {
		// Amavis: a decorated X-Spam-Score and an X-Spam-Flag, but no X-Spam-Status to fall back on.
		var message = new MimeMessage(Session.getInstance(new Properties()));
		message.setFrom("spammer@example.com");
		message.setRecipients(Message.RecipientType.TO, USER);
		message.setSubject("Cheap watches");
		message.setText("buy now");
		message.setHeader("X-Spam-Score", "7.3 (*******)");
		message.setHeader("X-Spam-Flag", "YES");
		message.saveChanges();
		greenMail.setUser(USER, USER, PASSWORD).deliver(message);

		var tools = new EmailTools();
		tools.emailService = service;
		var triage = tools.triageCompact(ACCOUNT, "INBOX", true, 0, 10);

		var block = triage.substring(triage.indexOf("Cheap watches"));
		block = block.substring(0, block.indexOf("\n\n"));
		assertTrue(block.contains("Spam score: 7.3"), "score should be parsed from the decorated header:\n" + block);
		assertTrue(block.contains("Spam-Flag: YES"), "the filter's verdict should be shown:\n" + block);
		// The plain fixtures carry no spam headers and must not show any spam line.
		var plain = triage.substring(triage.indexOf("[UID"), triage.indexOf("Cheap watches"));
		assertFalse(plain.contains("Spam"), plain);
	}

	@Test
	void readEmailShowsRecipientsHonestly() throws Exception {
		// Bcc delivery: no To header at all.
		var bccOnly = new MimeMessage(Session.getInstance(new Properties()));
		bccOnly.setFrom("news@example.com");
		bccOnly.setSubject("Newsletter via Bcc");
		bccOnly.setText("hi");
		bccOnly.saveChanges();
		greenMail.setUser(USER, USER, PASSWORD).deliver(bccOnly);
		// Copied in: a To and a Cc.
		var copied = new MimeMessage(Session.getInstance(new Properties()));
		copied.setFrom("boss@example.com");
		copied.setRecipients(Message.RecipientType.TO, "colleague@example.com");
		copied.setRecipients(Message.RecipientType.CC, USER + ", other@example.com");
		copied.setSubject("FYI");
		copied.setText("see below");
		copied.saveChanges();
		greenMail.setUser(USER, USER, PASSWORD).deliver(copied);

		var tools = new EmailTools();
		tools.emailService = service;

		var newsletter = tools.readEmail(ACCOUNT, "INBOX", uidOf("Newsletter via Bcc"), Optional.empty());
		assertTrue(newsletter.contains("To:      (none)"), newsletter);
		assertFalse(newsletter.contains("null"), newsletter);
		assertFalse(newsletter.contains("Cc:"), "no Cc line when there are no Cc recipients:\n" + newsletter);

		var fyi = tools.readEmail(ACCOUNT, "INBOX", uidOf("FYI"), Optional.empty());
		assertTrue(fyi.contains("To:      colleague@example.com"), fyi);
		assertTrue(fyi.contains("Cc:      " + USER + ", other@example.com"), fyi);
	}

	@Test
	void nonAsciiNamesAndSubjectsAreShownDecodedEverywhere() throws Exception {
		var message = new MimeMessage(Session.getInstance(new Properties()));
		message.setFrom(new jakarta.mail.internet.InternetAddress("faktura@example.com", "Bokföring AB", "UTF-8"));
		message.setRecipient(Message.RecipientType.TO,
				new jakarta.mail.internet.InternetAddress(USER, "Åsa Användare", "UTF-8"));
		message.setRecipient(Message.RecipientType.CC,
				new jakarta.mail.internet.InternetAddress("kollega@example.com", "Örjan Kollega", "UTF-8"));
		message.setSubject("Årsredovisning 2026", "UTF-8");
		message.setText("hej");
		message.saveChanges();
		// The wire form is encoded; that is what the IMAP server hands back.
		assertTrue(message.getHeader("From")[0].contains("=?UTF-8?"), message.getHeader("From")[0]);
		greenMail.setUser(USER, USER, PASSWORD).deliver(message);
		var tools = new EmailTools();
		tools.emailService = service;

		var listed = tools.listEmails(ACCOUNT, "INBOX", 0, 10, Optional.empty());
		assertTrue(listed.contains("Årsredovisning 2026"), listed);
		assertTrue(listed.contains("From: Bokföring AB <faktura@example.com>"), listed);

		var read = tools.readEmail(ACCOUNT, "INBOX", uidOf("Årsredovisning 2026"), Optional.empty());
		assertTrue(read.contains("From:    Bokföring AB <faktura@example.com>"), read);
		assertTrue(read.contains("To:      Åsa Användare <" + USER + ">"), read);
		assertTrue(read.contains("Cc:      Örjan Kollega <kollega@example.com>"), read);

		var compact = tools.triageCompact(ACCOUNT, "INBOX", false, 0, 10);
		assertTrue(compact.contains("From: Bokföring AB <faktura@example.com>"), compact);

		var full = tools.triageEmails(ACCOUNT, "INBOX", false, 0, 10);
		assertTrue(full.contains("Årsredovisning 2026"), full);
		assertTrue(full.contains("From: Bokföring AB <faktura@example.com>"), full);

		var searched = tools.searchEmails(ACCOUNT, "INBOX", "hej", 10);
		assertTrue(searched.contains("From: Bokföring AB <faktura@example.com>"), searched);

		assertFalse((listed + read + compact + full + searched).contains("=?UTF-8?"), "no encoded-words should leak");
	}

	@Test
	void sendEmailOverPlainSmtpDeliversToInbox() throws Exception {
		service.sendEmail(ACCOUNT, USER, null, null, "Sent via SMTP", "hello");

		assertTrue(greenMail.waitForIncomingEmail(5_000, 1), "GreenMail should have received the message");
		assertTrue(subjectsIn("INBOX").contains("Sent via SMTP"));
	}

	@Test
	void configuredFromAddressWithDisplayNameIsUsed() throws Exception {
		service.config = configWithFrom("Test Sender <sender@localhost>");

		service.sendEmail(ACCOUNT, USER, null, null, "From check", "hello");

		assertTrue(greenMail.waitForIncomingEmail(5_000, 1));
		var received = findReceived("From check");
		assertEquals("Test Sender <sender@localhost>", received.getFrom()[0].toString());
	}

	@Test
	void replyAllLeavesOwnAddressesOutButKeepsTheOthers() throws Exception {
		service.config = configWithFrom("Me <me@example.com>");
		// A message to us at two of our addresses plus a third party, from someone else.
		var original = new MimeMessage(Session.getInstance(new Properties()));
		original.setFrom("other@example.com");
		original.setRecipients(Message.RecipientType.TO, "me@example.com, " + USER);
		original.setRecipients(Message.RecipientType.CC, "third@example.com, me.example.com@elsewhere.org");
		original.setSubject("Group thread");
		original.setText("hi all");
		original.saveChanges();
		greenMail.setUser(USER, USER, PASSWORD).deliver(original);

		service.replyEmail(ACCOUNT, "INBOX", uidOf("Group thread"), "reply body", true, null, null);

		assertTrue(greenMail.waitForIncomingEmail(5_000, 1));
		var reply = findReceived("Re: Group thread");
		assertEquals("other@example.com", reply.getRecipients(Message.RecipientType.TO)[0].toString());
		var cc = java.util.Arrays.stream(reply.getRecipients(Message.RecipientType.CC)).map(Object::toString).sorted()
				.toList();
		// me@example.com (the From) and user@localhost (the username) are ours; the near-miss
		// "me.example.com@elsewhere.org" is not, and must not be dropped by a substring match.
		assertEquals(List.of("me.example.com@elsewhere.org", "third@example.com"), cc);
	}

	@Test
	void starttlsIsRequiredNotOpportunistic() throws Exception {
		// GreenMail's plain SMTP server does not offer STARTTLS. With starttls=true the send must be refused
		// instead of silently falling back to an unencrypted login.
		var strictService = new EmailService();
		strictService.config = config(ServerSetupTest.IMAP.getPort(), ServerSetupTest.SMTP.getPort(), true, false, 60);

		var e = assertThrows(MessagingException.class,
				() -> strictService.sendEmail(ACCOUNT, USER, null, null, "Must not be sent", "hello"));

		assertTrue(e.getMessage().toLowerCase().contains("starttls"), "unexpected failure reason: " + e.getMessage());
		assertFalse(subjectsIn("INBOX").contains("Must not be sent"));
	}

	// ── helpers ─────────────────────────────────────────────────────────

	private static EmailConfig config() {
		return config(ServerSetupTest.IMAP.getPort(), ServerSetupTest.SMTP.getPort(), false, false, 60);
	}

	private static EmailConfig config(
		int imapPort, int smtpPort, boolean smtpStarttls, boolean smtpSsl, int networkTimeoutSeconds) {
		return config(imapPort, smtpPort, smtpStarttls, smtpSsl, networkTimeoutSeconds, Optional.empty());
	}

	private static EmailConfig configWithFrom(String from) {
		return config(ServerSetupTest.IMAP.getPort(), ServerSetupTest.SMTP.getPort(), false, false, 60,
				Optional.of(from));
	}

	private static EmailConfig config(
		int imapPort, int smtpPort, boolean smtpStarttls, boolean smtpSsl, int networkTimeoutSeconds,
		Optional<String> from) {
		var imap = new EmailConfig.ImapConfig() {
			public String host() {
				return "localhost";
			}

			public int port() {
				return imapPort;
			}

			public String username() {
				return USER;
			}

			public String password() {
				return PASSWORD;
			}

			public Optional<Boolean> ssl() {
				return Optional.of(false);
			}
		};
		var smtp = new EmailConfig.SmtpConfig() {
			public String host() {
				return "localhost";
			}

			public int port() {
				return smtpPort;
			}

			public String username() {
				return USER;
			}

			public String password() {
				return PASSWORD;
			}

			public boolean starttls() {
				return smtpStarttls;
			}

			public Optional<Boolean> ssl() {
				return Optional.of(smtpSsl);
			}
		};
		var account = new EmailConfig.AccountConfig() {
			public EmailConfig.ImapConfig imap() {
				return imap;
			}

			public EmailConfig.SmtpConfig smtp() {
				return smtp;
			}

			public Optional<String> draftsFolder() {
				return Optional.empty();
			}

			public Optional<String> spamFolder() {
				return Optional.empty();
			}

			public Optional<String> trashFolder() {
				return Optional.empty();
			}

			public Optional<String> from() {
				return from;
			}
		};
		return new EmailConfig() {
			public Map<String, AccountConfig> accounts() {
				return Map.of(ACCOUNT, account);
			}

			public int networkTimeout() {
				return networkTimeoutSeconds;
			}
		};
	}

	private static MimeMessage findReceived(String subject) throws Exception {
		for (var m : greenMail.getReceivedMessages()) {
			if (subject.equals(m.getSubject())) {
				return m;
			}
		}
		throw new AssertionError("No received message with subject " + subject);
	}

	/** A second, independent IMAP session standing in for the user's regular mail client. */
	private static Store otherClient() throws Exception {
		var props = new Properties();
		props.put("mail.store.protocol", "imap");
		var store = Session.getInstance(props).getStore("imap");
		store.connect("localhost", ServerSetupTest.IMAP.getPort(), USER, PASSWORD);
		return store;
	}

	private static void flagDeletedFromAnotherClient(String subject) throws Exception {
		try (var store = otherClient()) {
			var inbox = store.getFolder("INBOX");
			inbox.open(Folder.READ_WRITE);
			try {
				for (var m : inbox.getMessages()) {
					if (subject.equals(m.getSubject())) {
						m.setFlag(Flags.Flag.DELETED, true);
					}
				}
			} finally {
				inbox.close(false); // no expunge: the flag stays, as in a client that defers purging
			}
		}
	}

	private long uidOf(String subject) throws Exception {
		for (var h : service.listEmails(ACCOUNT, "INBOX", 0, 100, "date")) {
			if (subject.equals(h.subject())) {
				return h.uid();
			}
		}
		throw new AssertionError("No message with subject " + subject + " in INBOX");
	}

	private static List<String> subjectsIn(String folderName) throws Exception {
		try (var store = otherClient()) {
			var folder = store.getFolder(folderName);
			folder.open(Folder.READ_ONLY);
			try {
				return java.util.Arrays.stream(folder.getMessages()).map(m -> {
					try {
						return m.getSubject();
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}).sorted().toList();
			} finally {
				folder.close(false);
			}
		}
	}

	private static boolean isFlaggedDeleted(String folderName, String subject) throws Exception {
		try (var store = otherClient()) {
			var folder = store.getFolder(folderName);
			folder.open(Folder.READ_ONLY);
			try {
				for (Message m : folder.search(new FlagTerm(new Flags(Flags.Flag.DELETED), true))) {
					if (subject.equals(m.getSubject())) {
						return true;
					}
				}
				return false;
			} finally {
				folder.close(false);
			}
		}
	}
}
