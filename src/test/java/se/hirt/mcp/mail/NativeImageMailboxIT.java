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
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the built server binary, over STDIO, against a live in-process IMAP and SMTP server: the
 * real mail library, the real MCP runtime and the real transport, in the artifact that ships. The
 * seeded message is built to touch the paths that are easy to get wrong in a native image or a
 * refactoring: an HTML alternative, an inline attachment with a non-ASCII encoded-word name, a
 * sender with a non-ASCII display name, and decorated spam headers.
 * <p>
 * Skipped unless {@code native.image.path} names the binary, as for {@link NativeImageSanityIT}:
 *
 * <pre>
 *   mvn test-compile failsafe:integration-test failsafe:verify -Dnative.image.path=target/mcp-email-server-...-runner
 * </pre>
 */
@EnabledIfSystemProperty(named = "native.image.path", matches = ".+")
class NativeImageMailboxIT {

	private static final String USER = "user@localhost";
	private static final String PASSWORD = "secret";
	private static final byte[] PDF_BYTES = "%PDF-1.4 not really a pdf".getBytes(StandardCharsets.UTF_8);
	private static final String PDF_NAME = "Faktura för året.pdf";

	@RegisterExtension
	static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP)
			.withConfiguration(GreenMailConfiguration.aConfig().withUser(USER, USER, PASSWORD));

	@TempDir
	Path workDir;

	private McpStdioClient server;

	@BeforeEach
	void startServer() throws Exception {
		var binary = Path.of(System.getProperty("native.image.path"));
		assertTrue(Files.exists(binary), "Native binary not found at: " + binary);
		greenMail.setUser(USER, USER, PASSWORD).deliver(seededMessage());

		var env = new HashMap<String, String>();
		env.put("EMAIL_ACCOUNTS_TEST_IMAP_HOST", "localhost");
		env.put("EMAIL_ACCOUNTS_TEST_IMAP_PORT", String.valueOf(ServerSetupTest.IMAP.getPort()));
		env.put("EMAIL_ACCOUNTS_TEST_IMAP_USERNAME", USER);
		env.put("EMAIL_ACCOUNTS_TEST_IMAP_PASSWORD", PASSWORD);
		env.put("EMAIL_ACCOUNTS_TEST_IMAP_SSL", "false");
		env.put("EMAIL_ACCOUNTS_TEST_SMTP_HOST", "localhost");
		env.put("EMAIL_ACCOUNTS_TEST_SMTP_PORT", String.valueOf(ServerSetupTest.SMTP.getPort()));
		env.put("EMAIL_ACCOUNTS_TEST_SMTP_USERNAME", USER);
		env.put("EMAIL_ACCOUNTS_TEST_SMTP_PASSWORD", PASSWORD);
		env.put("EMAIL_ACCOUNTS_TEST_SMTP_STARTTLS", "false");
		env.put("EMAIL_ACCOUNTS_TEST_FROM", "Test User <" + USER + ">");
		env.put("EMAIL_ALLOW_SENDING", "true");
		env.put("EMAIL_NETWORK_TIMEOUT", "15");
		server = new McpStdioClient(binary, workDir, env);
		var info = server.initialize();
		assertEquals("mcp-email-server", info.get("name").asText());
	}

	@AfterEach
	void stopServer() throws Exception {
		if (server != null) {
			server.close();
			assertEquals(List.of(), server.unparsableStdout(), "stdout must carry nothing but JSON-RPC");
		}
	}

	@Test
	void readsTheSeededMessageAsTheModelShouldSeeIt() throws Exception {
		var tools = server.listTools();
		assertTrue(tools.containsAll(List.of("listAccounts", "readEmail", "moveEmails", "saveDraft", "getTrashFolder")),
				"tool list: " + tools);

		assertOk("listAccounts", call("listAccounts"), "Configured accounts: test");
		assertOk("listFolderTree", call("listFolderTree", "account", "test"), "INBOX");

		long uid = uidOf("Faktura");
		var read = assertOk("readEmail", call("readEmail", "account", "test", "folder", "INBOX", "uid", uid));
		assertContains(read, "From:    Bokföring AB <faktura@example.com>", "sender display name decoded");
		assertContains(read, "Se **bifogad** faktura.", "HTML alternative rendered as markdown");
		assertContains(read, "Attachments: " + PDF_NAME, "inline attachment listed with its decoded name");

		var triage = assertOk("triageCompact", call("triageCompact", "account", "test", "folder", "INBOX", "unreadOnly",
				true, "offset", 0, "limit", 10));
		assertContains(triage, "Spam score: 7.3", "decorated X-Spam-Score parsed");
		assertContains(triage, "Spam-Flag: YES", "filter verdict shown");

		var attachment = server.call("getAttachment",
				Map.of("account", "test", "folder", "INBOX", "uid", uid, "attachmentName", PDF_NAME));
		assertFalse(attachment.isError(), "getAttachment: " + attachment.text());
		// Not a real PDF, so text extraction fails and the bytes come back as a blob resource.
		var resource = attachment.content().path("resource");
		assertEquals("application/pdf", resource.path("mimeType").asText(), attachment.content().toString());
		assertArrayEquals(PDF_BYTES, Base64.getDecoder().decode(resource.path("blob").asText()));
	}

	@Test
	void movesDraftsAndSendsOverTheLiveServers() throws Exception {
		long uid = uidOf("Faktura");
		assertOk("createFolder", call("createFolder", "account", "test", "folderName", "Archive"), "Created folder");
		assertError("createFolder again", call("createFolder", "account", "test", "folderName", "Archive"),
				"already exists");

		assertOk("moveEmails", call("moveEmails", "account", "test", "sourceFolder", "INBOX", "uids",
				String.valueOf(uid), "targetFolder", "Archive", "markRead", true), "Moved 1 message(s)");
		assertContains(assertOk("listEmails Archive", listEmails("Archive")), "Faktura", "message in Archive");
		assertFalse(assertOk("listEmails INBOX", listEmails("INBOX")).contains("Faktura"), "message gone from INBOX");

		assertOk("createFolder Drafts", call("createFolder", "account", "test", "folderName", "Drafts"));
		assertOk("saveDraft", call("saveDraft", "account", "test", "to", "someone@example.com", "cc", "", "bcc", "",
				"subject", "Utkast", "body", "hej", "inReplyToFolder", "", "inReplyToUid", 0), "Draft saved");
		assertContains(assertOk("listEmails Drafts", listEmails("Drafts")), "Utkast", "draft present");

		assertOk("sendEmail", call("sendEmail", "account", "test", "to", USER, "cc", "", "bcc", "", "subject",
				"Skickat", "body", "via native smtp"), "Email sent");
		assertTrue(greenMail.waitForIncomingEmail(5_000, 2), "sent message should arrive");
		var sent = assertOk("searchEmails",
				call("searchEmails", "account", "test", "folder", "INBOX", "query", "Skickat", "limit", 10));
		assertContains(sent, "From: Test User <" + USER + ">", "configured From with display name");
	}

	@Test
	void failuresAreReportedAsToolErrors() throws Exception {
		assertError("getTrashFolder with none", call("getTrashFolder", "account", "test"), "Could not auto-detect");
		assertError("unknown account", call("listFolders", "account", "nope"), "Unknown account");
		assertError("deleteEmail disabled", call("deleteEmail", "account", "test", "folder", "INBOX", "uid", 1),
				"deleteEmail is disabled");
		assertError("bad UIDs", call("moveEmails", "account", "test", "sourceFolder", "INBOX", "uids", "abc",
				"targetFolder", "Archive", "markRead", false), "Error moving emails");
	}

	// ── helpers ─────────────────────────────────────────────────────────

	private static MimeMessage seededMessage() throws Exception {
		var message = new MimeMessage(Session.getInstance(new Properties()));
		message.setFrom(new InternetAddress("faktura@example.com", "Bokföring AB", "UTF-8"));
		message.setRecipients(Message.RecipientType.TO, USER);
		message.setSubject("Faktura");
		message.setHeader("X-Spam-Score", "7.3 (*******)");
		message.setHeader("X-Spam-Flag", "YES");

		var text = new MimeBodyPart();
		text.setText("Se bifogad faktura.");
		var html = new MimeBodyPart();
		html.setContent("<html><body><p>Se <b>bifogad</b> faktura.</p></body></html>", "text/html; charset=UTF-8");
		var alternative = new MimeMultipart("alternative");
		alternative.addBodyPart(text);
		alternative.addBodyPart(html);
		var body = new MimeBodyPart();
		body.setContent(alternative);

		var pdf = new MimeBodyPart();
		pdf.setDataHandler(new DataHandler(new ByteArrayDataSource(PDF_BYTES, "application/pdf")));
		// Inline disposition and an RFC 2047 encoded-word file name, as Outlook would send it.
		pdf.setHeader("Content-Disposition", "inline; filename=\"=?UTF-8?Q?Faktura_f=C3=B6r_=C3=A5ret.pdf?=\"");

		var mixed = new MimeMultipart("mixed");
		mixed.addBodyPart(body);
		mixed.addBodyPart(pdf);
		message.setContent(mixed);
		message.saveChanges();
		return message;
	}

	private McpStdioClient.ToolResult call(String tool, Object ... keyValues) throws Exception {
		var args = new HashMap<String, Object>();
		for (int i = 0; i < keyValues.length; i += 2) {
			args.put((String) keyValues[i], keyValues[i + 1]);
		}
		return server.call(tool, args);
	}

	private McpStdioClient.ToolResult listEmails(String folder) throws Exception {
		return call("listEmails", "account", "test", "folder", folder, "offset", 0, "limit", 10);
	}

	private long uidOf(String subject) throws Exception {
		var listed = assertOk("listEmails INBOX", listEmails("INBOX"));
		var m = Pattern.compile("\\[UID (\\d+)\\] " + Pattern.quote(subject)).matcher(listed);
		assertTrue(m.find(), "no message with subject " + subject + " in:\n" + listed);
		return Long.parseLong(m.group(1));
	}

	private static String assertOk(String label, McpStdioClient.ToolResult result, String ... expected) {
		assertFalse(result.isError(), label + " failed: " + result.text());
		assertNotNull(result.text(), label + " returned no text");
		for (var e : expected) {
			assertContains(result.text(), e, label);
		}
		return result.text();
	}

	private static void assertError(String label, McpStdioClient.ToolResult result, String expected) {
		assertTrue(result.isError(), label + " should be a tool error, got: " + result.text());
		assertContains(result.text(), expected, label);
	}

	private static void assertContains(String text, String expected, String label) {
		assertTrue(text != null && text.contains(expected),
				label + ": expected to find \"" + expected + "\" in:\n" + text);
	}
}
