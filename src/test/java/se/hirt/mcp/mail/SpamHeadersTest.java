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

/** The spam score and verdict as the various filters write them. */
class SpamHeadersTest {

	@Test
	void bareNumberInSpamScore() {
		assertEquals(3.2, EmailService.parseSpamScore("3.2", null));
		assertEquals(-1.9, EmailService.parseSpamScore(" -1.9 ", null));
		assertEquals(12.0, EmailService.parseSpamScore("12", null));
	}

	@Test
	void amavisStyleScoreWithStars() {
		assertEquals(3.2, EmailService.parseSpamScore("3.2 (***)", null));
		assertEquals(-1.9, EmailService.parseSpamScore("-1.9 (-)", null));
	}

	@Test
	void scoreOverThresholdStyle() {
		assertEquals(3.21, EmailService.parseSpamScore("3.21 / 5.0", null));
	}

	@Test
	void scorePrefixedWithKeyword() {
		assertEquals(3.2, EmailService.parseSpamScore("score=3.2", null));
	}

	@Test
	void starsOnlyIsNotAScore() {
		assertNull(EmailService.parseSpamScore("***", null));
	}

	@Test
	void fallsBackToSpamStatusWithScoreOrHits() {
		assertEquals(12.3, EmailService.parseSpamScore(null, "Yes, score=12.3 required=5.0 tests=BAYES_99"));
		assertEquals(-0.5, EmailService.parseSpamScore(null, "No, hits=-0.5 required=5.0"));
		assertEquals(12.3, EmailService.parseSpamScore("***", "Yes, score=12.3 required=5.0"),
				"an unparsable score header should not stop the fallback");
	}

	@Test
	void noHeadersMeansNoScore() {
		assertNull(EmailService.parseSpamScore(null, null));
		assertNull(EmailService.parseSpamScore(null, "No"));
	}

	@Test
	void verdictFromFlagOrStatus() {
		assertTrue(EmailService.parseSpamFlag("YES", null));
		assertTrue(EmailService.parseSpamFlag(" yes ", null));
		assertFalse(EmailService.parseSpamFlag("NO", null));
		assertTrue(EmailService.parseSpamFlag(null, "Yes, score=7.1 required=5.0"));
		assertFalse(EmailService.parseSpamFlag(null, "No, score=0.1 required=5.0"));
		assertFalse(EmailService.parseSpamFlag(null, null));
	}
}
