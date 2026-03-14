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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sanity test for the native image binary. Starts the native executable,
 * sends an MCP initialize + tools/list request over STDIO, and verifies
 * the server responds with its tool list.
 * <p>
 * Skipped unless {@code native.image.path} system property is set, e.g.:
 * <pre>
 *   mvn verify -Dnative -Dnative.image.path=target/mcp-email-server-...-runner.exe
 * </pre>
 * The release workflow can set this after the native build completes.
 */
class NativeImageSanityIT {

    @Test
    @EnabledIfSystemProperty(named = "native.image.path", matches = ".+")
    void nativeBinaryRespondsToMcpInitialize() throws Exception {
        String binaryPath = System.getProperty("native.image.path");
        Path binary = Path.of(binaryPath);
        assertTrue(Files.exists(binary), "Native binary not found at: " + binaryPath);

        ProcessBuilder pb = new ProcessBuilder(
                binary.toAbsolutePath().toString(),
                "-Dquarkus.mcp.server.stdio.enabled=true"
        );
        pb.redirectErrorStream(false);
        Process process = pb.start();

        try {
            OutputStream stdin = process.getOutputStream();
            InputStream stdout = process.getInputStream();

            // Send MCP initialize request
            String initRequest = "{\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
                    + "\"capabilities\":{},\"clientInfo\":{\"name\":\"sanity-test\",\"version\":\"1.0\"}},"
                    + "\"jsonrpc\":\"2.0\",\"id\":0}\n";
            stdin.write(initRequest.getBytes(StandardCharsets.UTF_8));
            stdin.flush();

            // Read response (with timeout)
            String initResponse = readResponse(stdout, 10_000);
            assertNotNull(initResponse, "No response from native binary for initialize");
            assertTrue(initResponse.contains("\"serverInfo\""),
                    "Initialize response should contain serverInfo: " + initResponse);
            assertTrue(initResponse.contains("mcp-email-server"),
                    "Server name should be mcp-email-server: " + initResponse);

            // Send initialized notification + tools/list
            String initialized = "{\"method\":\"notifications/initialized\",\"jsonrpc\":\"2.0\"}\n";
            stdin.write(initialized.getBytes(StandardCharsets.UTF_8));
            stdin.flush();

            String toolsListRequest = "{\"method\":\"tools/list\",\"params\":{},\"jsonrpc\":\"2.0\",\"id\":1}\n";
            stdin.write(toolsListRequest.getBytes(StandardCharsets.UTF_8));
            stdin.flush();

            String toolsResponse = readResponse(stdout, 10_000);
            assertNotNull(toolsResponse, "No response from native binary for tools/list");
            assertTrue(toolsResponse.contains("\"tools\""),
                    "tools/list response should contain tools array: " + toolsResponse);
            assertTrue(toolsResponse.contains("listAccounts"),
                    "tools/list should include listAccounts: " + toolsResponse);

            // Send listAccounts call (no IMAP needed — just reads config)
            String listAccounts = "{\"method\":\"tools/call\",\"params\":{\"name\":\"listAccounts\",\"arguments\":{}},"
                    + "\"jsonrpc\":\"2.0\",\"id\":2}\n";
            stdin.write(listAccounts.getBytes(StandardCharsets.UTF_8));
            stdin.flush();

            String accountsResponse = readResponse(stdout, 10_000);
            assertNotNull(accountsResponse, "No response from native binary for listAccounts");
            assertTrue(accountsResponse.contains("\"result\""),
                    "listAccounts should return a result: " + accountsResponse);

        } finally {
            process.destroyForcibly();
            process.waitFor();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "native.image.path", matches = ".+")
    void nativeBinaryPdfExtractionWorks() throws Exception {
        String binaryPath = System.getProperty("native.image.path");
        Path binary = Path.of(binaryPath);
        assertTrue(Files.exists(binary), "Native binary not found at: " + binaryPath);

        ProcessBuilder pb = new ProcessBuilder(
                binary.toAbsolutePath().toString(),
                "-Dquarkus.mcp.server.stdio.enabled=true"
        );
        pb.redirectErrorStream(false);
        Process process = pb.start();

        try {
            OutputStream stdin = process.getOutputStream();
            InputStream stdout = process.getInputStream();

            // MCP initialize handshake
            String initRequest = "{\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
                    + "\"capabilities\":{},\"clientInfo\":{\"name\":\"sanity-test\",\"version\":\"1.0\"}},"
                    + "\"jsonrpc\":\"2.0\",\"id\":0}\n";
            stdin.write(initRequest.getBytes(StandardCharsets.UTF_8));
            stdin.flush();

            String initResponse = readResponse(stdout, 10_000);
            assertNotNull(initResponse, "No response from native binary for initialize");

            String initialized = "{\"method\":\"notifications/initialized\",\"jsonrpc\":\"2.0\"}\n";
            stdin.write(initialized.getBytes(StandardCharsets.UTF_8));
            stdin.flush();

            // Call selfTestPdf — creates a PDF in memory and extracts text using iText
            String selfTestRequest = "{\"method\":\"tools/call\",\"params\":{\"name\":\"selfTestPdf\",\"arguments\":{}},"
                    + "\"jsonrpc\":\"2.0\",\"id\":3}\n";
            stdin.write(selfTestRequest.getBytes(StandardCharsets.UTF_8));
            stdin.flush();

            String selfTestResponse = readResponse(stdout, 15_000);
            assertNotNull(selfTestResponse, "No response from native binary for selfTestPdf");
            assertTrue(selfTestResponse.contains("PASSED"),
                    "PDF self-test should pass in native image: " + selfTestResponse);
            assertTrue(selfTestResponse.contains("Hello from mcp-email-server"),
                    "PDF self-test should contain expected text: " + selfTestResponse);

        } finally {
            process.destroyForcibly();
            process.waitFor();
        }
    }

    /**
     * Reads a single JSON-RPC response line from the process stdout,
     * with a timeout to avoid hanging forever.
     */
    private String readResponse(InputStream stdout, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        StringBuilder sb = new StringBuilder();
        while (System.currentTimeMillis() < deadline) {
            if (stdout.available() > 0) {
                int b = stdout.read();
                if (b == -1) break;
                if (b == '\n') {
                    String line = sb.toString().trim();
                    if (!line.isEmpty()) return line;
                    sb.setLength(0);
                } else {
                    sb.append((char) b);
                }
            } else {
                Thread.sleep(50);
            }
        }
        String remaining = sb.toString().trim();
        return remaining.isEmpty() ? null : remaining;
    }
}
