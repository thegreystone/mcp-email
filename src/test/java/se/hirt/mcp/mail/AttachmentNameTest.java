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

import static org.junit.jupiter.api.Assertions.*;

/** Attachment names as the model sees them: decoded, or null when there is no usable name. */
class AttachmentNameTest {

	@Test
	void plainNamesPassThrough() {
		assertEquals("report.pdf", EmailService.decodeAttachmentName("report.pdf"));
		assertEquals("årsredovisning.pdf", EmailService.decodeAttachmentName("årsredovisning.pdf"));
	}

	@Test
	void encodedWordsAreDecoded() {
		assertEquals("Faktura för året.pdf",
				EmailService.decodeAttachmentName("=?UTF-8?Q?Faktura_f=C3=B6r_=C3=A5ret.pdf?="));
		assertEquals("Bokföring.pdf", EmailService.decodeAttachmentName("=?UTF-8?B?Qm9rZsO2cmluZy5wZGY=?="));
		assertEquals("Ärende 12.pdf", EmailService.decodeAttachmentName("=?ISO-8859-1?Q?=C4rende_12.pdf?="));
	}

	@Test
	void unknownCharsetKeepsTheRawName() {
		var raw = "=?X-NO-SUCH-CHARSET?Q?abc?=";
		assertEquals(raw, EmailService.decodeAttachmentName(raw));
	}

	@Test
	void missingOrBlankNameIsNull() {
		assertNull(EmailService.decodeAttachmentName(null));
		assertNull(EmailService.decodeAttachmentName(""));
		assertNull(EmailService.decodeAttachmentName("   "));
	}
}
