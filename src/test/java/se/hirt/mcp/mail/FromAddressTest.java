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

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** How the per-account From address is derived from the configuration. */
class FromAddressTest {

	@Test
	void defaultsToTheSmtpUsername() throws Exception {
		var from = EmailService.fromAddress(account("jane@example.com", Optional.empty()));
		assertEquals("jane@example.com", from.getAddress());
		assertNull(from.getPersonal());
	}

	@Test
	void blankOrUnexpandedPlaceholderMeansUnset() throws Exception {
		assertEquals("jane@example.com",
				EmailService.fromAddress(account("jane@example.com", Optional.of("  "))).getAddress());
		assertEquals("jane@example.com",
				EmailService.fromAddress(account("jane@example.com", Optional.of("${user_config.from}"))).getAddress());
	}

	@Test
	void bareAddressAndDisplayNameFormsAreBothAccepted() throws Exception {
		var bare = EmailService.fromAddress(account("login123", Optional.of("jane@example.com")));
		assertEquals("jane@example.com", bare.getAddress());
		assertNull(bare.getPersonal());

		var named = EmailService.fromAddress(account("login123", Optional.of("Jane Doe <jane@example.com>")));
		assertEquals("jane@example.com", named.getAddress());
		assertEquals("Jane Doe", named.getPersonal());
	}

	@Test
	void nonAddressUsernameStillYieldsAFromRatherThanFailing() throws Exception {
		assertEquals("login123", EmailService.fromAddress(account("login123", Optional.empty())).getAddress());
	}

	@Test
	void moreThanOneAddressIsRejected() {
		var e = assertThrows(MessagingException.class,
				() -> EmailService.fromAddress(account("x", Optional.of("a@example.com, b@example.com"))));
		assertTrue(e.getMessage().contains("single address"), e.getMessage());
	}

	private static EmailConfig.AccountConfig account(String smtpUsername, Optional<String> from) {
		var smtp = new EmailConfig.SmtpConfig() {
			public String host() {
				return "smtp.example.com";
			}

			public int port() {
				return 587;
			}

			public String username() {
				return smtpUsername;
			}

			public String password() {
				return "p";
			}

			public boolean starttls() {
				return true;
			}

			public Optional<Boolean> ssl() {
				return Optional.empty();
			}
		};
		return new EmailConfig.AccountConfig() {
			public EmailConfig.ImapConfig imap() {
				return null;
			}

			public EmailConfig.SmtpConfig smtp() {
				return smtp;
			}

			public Optional<String> from() {
				return from;
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
		};
	}
}
