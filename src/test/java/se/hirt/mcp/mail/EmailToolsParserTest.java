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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmailToolsParserTest {

    // ── parseUids ──────────────────────────────────────────────────────

    @Test
    void parseUids_singleUid() {
        assertEquals(List.of(42L), EmailTools.parseUids("42"));
    }

    @Test
    void parseUids_multipleUids() {
        assertEquals(List.of(1L, 2L, 3L), EmailTools.parseUids("1,2,3"));
    }

    @Test
    void parseUids_withWhitespace() {
        assertEquals(List.of(10L, 20L, 30L), EmailTools.parseUids(" 10 , 20 , 30 "));
    }

    @Test
    void parseUids_trailingComma() {
        assertEquals(List.of(5L, 6L), EmailTools.parseUids("5,6,"));
    }

    @Test
    void parseUids_largeUids() {
        assertEquals(List.of(999999999L), EmailTools.parseUids("999999999"));
    }

    @Test
    void parseUids_invalidThrows() {
        assertThrows(NumberFormatException.class, () -> EmailTools.parseUids("abc"));
    }

    @Test
    void parseUids_mixedValidInvalidThrows() {
        assertThrows(NumberFormatException.class, () -> EmailTools.parseUids("1,abc,3"));
    }

    // ── parseBatchMoves ────────────────────────────────────────────────

    @Test
    void parseBatchMoves_singleGroup() {
        var result = EmailTools.parseBatchMoves("Archive:101,102");
        assertEquals(1, result.size());
        assertEquals(List.of(101L, 102L), result.get("Archive"));
    }

    @Test
    void parseBatchMoves_multipleGroups() {
        var result = EmailTools.parseBatchMoves("Spam:201,202;Archive:301");
        assertEquals(2, result.size());
        assertEquals(List.of(201L, 202L), result.get("Spam"));
        assertEquals(List.of(301L), result.get("Archive"));
    }

    @Test
    void parseBatchMoves_withWhitespace() {
        var result = EmailTools.parseBatchMoves(" Archive : 1 , 2 ; Spam : 3 ");
        assertEquals(2, result.size());
        assertEquals(List.of(1L, 2L), result.get("Archive"));
        assertEquals(List.of(3L), result.get("Spam"));
    }

    @Test
    void parseBatchMoves_trailingSemicolon() {
        var result = EmailTools.parseBatchMoves("Archive:1;");
        assertEquals(1, result.size());
        assertEquals(List.of(1L), result.get("Archive"));
    }

    @Test
    void parseBatchMoves_emptyString() {
        var result = EmailTools.parseBatchMoves("");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseBatchMoves_noColon_skipped() {
        var result = EmailTools.parseBatchMoves("garbage;Archive:1");
        assertEquals(1, result.size());
        assertEquals(List.of(1L), result.get("Archive"));
    }

    @Test
    void parseBatchMoves_emptyFolder_skipped() {
        var result = EmailTools.parseBatchMoves(":1,2");
        assertTrue(result.isEmpty());
    }

    @Test
    void parseBatchMoves_duplicateFoldersMerged() {
        var result = EmailTools.parseBatchMoves("Archive:1,2;Archive:3");
        assertEquals(1, result.size());
        assertEquals(List.of(1L, 2L, 3L), result.get("Archive"));
    }

    @Test
    void parseBatchMoves_nestedFolderNames() {
        var result = EmailTools.parseBatchMoves("lists/openjdk:101;[Gmail]/Spam:201,202");
        assertEquals(2, result.size());
        assertEquals(List.of(101L), result.get("lists/openjdk"));
        assertEquals(List.of(201L, 202L), result.get("[Gmail]/Spam"));
    }

    @Test
    void parseBatchMoves_preservesInsertionOrder() {
        var result = EmailTools.parseBatchMoves("Zebra:1;Alpha:2;Middle:3");
        var keys = List.copyOf(result.keySet());
        assertEquals(List.of("Zebra", "Alpha", "Middle"), keys);
    }
}
