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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal MCP client over STDIO for driving a server binary from a test: starts the process,
 * sends JSON-RPC requests one line each, and collects the responses by id on a reader thread.
 */
final class McpStdioClient implements AutoCloseable {

	/** One tool call's outcome: the error flag and the first content item's text (or null). */
	record ToolResult(boolean isError, String text, JsonNode content) {
	}

	private static final ObjectMapper JSON = new ObjectMapper();

	private final Process process;
	private final OutputStream stdin;
	private final Map<Integer, JsonNode> responses = new ConcurrentHashMap<>();
	private final AtomicInteger nextId = new AtomicInteger();
	private final StringBuilder stderr = new StringBuilder();
	private final List<String> unparsableStdout = new ArrayList<>();

	McpStdioClient(Path binary, Path workDir, Map<String, String> env) throws IOException {
		var pb = new ProcessBuilder(binary.toAbsolutePath().toString(), "-Dquarkus.mcp.server.stdio.enabled=true",
				"-Duser.dir=" + workDir.toAbsolutePath());
		pb.directory(workDir.toFile());
		pb.environment().putAll(env);
		process = pb.start();
		stdin = process.getOutputStream();
		pump(process.getInputStream(), this::onStdoutLine);
		pump(process.getErrorStream(), line -> {
			synchronized (stderr) {
				stderr.append(line).append('\n');
			}
		});
	}

	private void pump(java.io.InputStream in, java.util.function.Consumer<String> onLine) {
		var t = new Thread(() -> {
			try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					onLine.accept(line);
				}
			} catch (IOException ignored) {
			}
		});
		t.setDaemon(true);
		t.start();
	}

	private void onStdoutLine(String line) {
		if (line.isBlank()) {
			return;
		}
		try {
			var msg = JSON.readTree(line);
			if (msg.hasNonNull("id")) {
				responses.put(msg.get("id").asInt(), msg);
			}
		} catch (IOException e) {
			synchronized (unparsableStdout) {
				unparsableStdout.add(line);
			}
		}
	}

	private void send(ObjectNode message) throws IOException {
		stdin.write((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
		stdin.flush();
	}

	JsonNode request(String method, JsonNode params, Duration timeout) throws IOException, InterruptedException {
		int id = nextId.getAndIncrement();
		var msg = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
		msg.set("params", params != null ? params : JSON.createObjectNode());
		send(msg);
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			var response = responses.remove(id);
			if (response != null) {
				return response;
			}
			if (!process.isAlive()) {
				throw new IllegalStateException("server exited with " + process.exitValue() + " while waiting for "
						+ method + "\nstderr:\n" + stderr());
			}
			Thread.sleep(20);
		}
		throw new IllegalStateException("no response to " + method + " within " + timeout + "\nstderr:\n" + stderr());
	}

	void notify(String method) throws IOException {
		send(JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method));
	}

	/** The initialize handshake; returns the server's serverInfo. */
	JsonNode initialize() throws IOException, InterruptedException {
		var params = JSON.createObjectNode().put("protocolVersion", "2025-11-25");
		params.set("capabilities", JSON.createObjectNode());
		params.set("clientInfo", JSON.createObjectNode().put("name", "mcp-email-it").put("version", "1"));
		var result = request("initialize", params, Duration.ofSeconds(30)).get("result");
		notify("notifications/initialized");
		return result.get("serverInfo");
	}

	List<String> listTools() throws IOException, InterruptedException {
		var tools = request("tools/list", null, Duration.ofSeconds(30)).get("result").get("tools");
		var names = new ArrayList<String>();
		tools.forEach(t -> names.add(t.get("name").asText()));
		return names;
	}

	ToolResult call(String tool, Map<String, Object> arguments) throws IOException, InterruptedException {
		var params = JSON.createObjectNode().put("name", tool);
		params.set("arguments", JSON.valueToTree(arguments));
		var response = request("tools/call", params, Duration.ofSeconds(60));
		if (response.has("error")) {
			return new ToolResult(true, "JSON-RPC error: " + response.get("error"), null);
		}
		var result = response.get("result");
		var content = result.path("content");
		var first = content.isArray() && content.size() > 0 ? content.get(0) : null;
		var text = first != null && first.hasNonNull("text") ? first.get("text").asText() : null;
		return new ToolResult(result.path("isError").asBoolean(false), text, first);
	}

	String stderr() {
		synchronized (stderr) {
			return stderr.toString();
		}
	}

	List<String> unparsableStdout() {
		synchronized (unparsableStdout) {
			return List.copyOf(unparsableStdout);
		}
	}

	@Override
	public void close() throws InterruptedException {
		try {
			stdin.close();
		} catch (IOException ignored) {
		}
		if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
			process.destroyForcibly();
			process.waitFor();
		}
	}
}
