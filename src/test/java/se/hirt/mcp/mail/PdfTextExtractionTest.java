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

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.AreaBreak;
import com.itextpdf.layout.element.Paragraph;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PdfTextExtractionTest {

    @Test
    void extractTextFromSimplePdf() throws Exception {
        byte[] pdfBytes = createTestPdf("Hello, World!", "This is a test PDF.");
        String text = PdfTextExtractorUtil.extractText(pdfBytes);
        assertTrue(text.contains("Hello, World!"), "Should contain first paragraph: " + text);
        assertTrue(text.contains("This is a test PDF."), "Should contain second paragraph: " + text);
    }

    @Test
    void extractTextFromMultiPagePdf() throws Exception {
        var baos = new ByteArrayOutputStream();
        try (var doc = new Document(new PdfDocument(new PdfWriter(baos)))) {
            doc.add(new Paragraph("Page one content."));
            doc.add(new AreaBreak());
            doc.add(new Paragraph("Page two content."));
        }
        String text = PdfTextExtractorUtil.extractText(baos.toByteArray());
        assertTrue(text.contains("Page one content."), "Should contain page 1 text: " + text);
        assertTrue(text.contains("Page two content."), "Should contain page 2 text: " + text);
    }

    @Test
    void extractTextFromEmptyPdf() throws Exception {
        var baos = new ByteArrayOutputStream();
        try (var doc = new Document(new PdfDocument(new PdfWriter(baos)))) {
            doc.add(new Paragraph(""));
        }
        String text = PdfTextExtractorUtil.extractText(baos.toByteArray());
        assertNotNull(text);
    }

    @Test
    void invalidPdfThrowsException() {
        assertThrows(Exception.class, () -> PdfTextExtractorUtil.extractText(new byte[]{1, 2, 3}));
    }

    private byte[] createTestPdf(String... paragraphs) throws Exception {
        var baos = new ByteArrayOutputStream();
        try (var doc = new Document(new PdfDocument(new PdfWriter(baos)))) {
            for (String text : paragraphs) {
                doc.add(new Paragraph(text));
            }
        }
        return baos.toByteArray();
    }
}
