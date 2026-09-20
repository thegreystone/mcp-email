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
	void sendEmailOverPlainSmtpDeliversToInbox() throws Exception {
		service.sendEmail(ACCOUNT, USER, null, null, "Sent via SMTP", "hello");

		assertTrue(greenMail.waitForIncomingEmail(5_000, 1), "GreenMail should have received the message");
		assertTrue(subjectsIn("INBOX").contains("Sent via SMTP"));
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
