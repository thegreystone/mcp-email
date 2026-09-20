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

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The TLS mode follows the port when not set explicitly, and an explicit value always wins. */
class TransportDefaultsTest {

	@Test
	void imapDefaultsToTlsExceptOnPort143() {
		assertTrue(EmailService.imapSsl(imap(993, Optional.empty())));
		assertTrue(EmailService.imapSsl(imap(9993, Optional.empty())), "non-standard ports stay on the safe default");
		assertFalse(EmailService.imapSsl(imap(143, Optional.empty())));
	}

	@Test
	void imapExplicitSettingWins() {
		assertTrue(EmailService.imapSsl(imap(143, Optional.of(true))));
		assertFalse(EmailService.imapSsl(imap(993, Optional.of(false))));
	}

	@Test
	void smtpDefaultsToStarttlsExceptOnPort465() {
		assertFalse(EmailService.smtpSsl(smtp(587, Optional.empty())), "the existing port 587 setup is unchanged");
		assertFalse(EmailService.smtpSsl(smtp(25, Optional.empty())));
		assertTrue(EmailService.smtpSsl(smtp(465, Optional.empty())));
	}

	@Test
	void smtpExplicitSettingWins() {
		assertTrue(EmailService.smtpSsl(smtp(2525, Optional.of(true))));
		assertFalse(EmailService.smtpSsl(smtp(465, Optional.of(false))));
	}

	private static EmailConfig.ImapConfig imap(int port, Optional<Boolean> ssl) {
		return new EmailConfig.ImapConfig() {
			public String host() {
				return "imap.example.com";
			}

			public int port() {
				return port;
			}

			public String username() {
				return "u";
			}

			public String password() {
				return "p";
			}

			public Optional<Boolean> ssl() {
				return ssl;
			}
		};
	}

	private static EmailConfig.SmtpConfig smtp(int port, Optional<Boolean> ssl) {
		return new EmailConfig.SmtpConfig() {
			public String host() {
				return "smtp.example.com";
			}

			public int port() {
				return port;
			}

			public String username() {
				return "u";
			}

			public String password() {
				return "p";
			}

			public boolean starttls() {
				return true;
			}

			public Optional<Boolean> ssl() {
				return ssl;
			}
		};
	}
}
