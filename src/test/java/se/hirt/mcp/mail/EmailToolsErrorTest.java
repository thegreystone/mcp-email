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

import io.quarkiverse.mcp.server.ToolCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tool failures must surface as {@link ToolCallException}, which the MCP runtime reports with
 * {@code isError: true}, never as a string that a client would take for a successful result. No
 * server is needed: every case here fails before a connection is attempted.
 */
class EmailToolsErrorTest {

	private EmailTools tools;

	@BeforeEach
	void setUp() {
		var service = new EmailService();
		service.config = new EmailConfig() {
			public Map<String, AccountConfig> accounts() {
				return Map.of();
			}

			public int networkTimeout() {
				return 1;
			}
		};
		tools = new EmailTools();
		tools.emailService = service;
		tools.allowSending = false;
		tools.allowDeletion = false;
	}

	@Test
	void serviceFailureIsWrappedWithContext() {
		var e = assertThrows(ToolCallException.class, () -> tools.listFolders("nope"));
		assertTrue(e.getMessage().startsWith("Error listing folders: Unknown account: nope"), e.getMessage());
	}

	@Test
	void inputValidationFailureIsNotDoubleWrapped() {
		var e = assertThrows(ToolCallException.class, () -> tools.moveEmails("a", "INBOX", " , ", "Archive", false));
		assertEquals("No valid UIDs provided.", e.getMessage());
	}

	@Test
	void unparsableUidsAreReportedAsAnError() {
		var e = assertThrows(ToolCallException.class,
				() -> tools.setEmailFlags("a", "INBOX", "12,abc", "true", "", "", ""));
		assertTrue(e.getMessage().startsWith("Error setting flags: "), e.getMessage());
	}

	@Test
	void disabledToolsAreErrors() {
		var send = assertThrows(ToolCallException.class,
				() -> tools.sendEmail("a", "to@example.com", "", "", "Subject", "Body"));
		assertTrue(send.getMessage().contains("sendEmail is disabled"), send.getMessage());
		var delete = assertThrows(ToolCallException.class, () -> tools.deleteEmail("a", "INBOX", 1));
		assertTrue(delete.getMessage().contains("deleteEmail is disabled"), delete.getMessage());
	}

	@Test
	void selfTestStillReportsSuccessAsPlainText() {
		assertTrue(tools.selfTestPdf().startsWith("PDF self-test PASSED"));
	}
}
