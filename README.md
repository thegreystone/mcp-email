# Email MCP Server

[![Build](https://github.com/thegreystone/mcp-email/actions/workflows/build.yml/badge.svg)](https://github.com/thegreystone/mcp-email/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/thegreystone/mcp-email)](https://github.com/thegreystone/mcp-email/releases/latest)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](https://adoptium.net/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.21-blueviolet)](https://quarkus.io/)
[![GraalVM Native](https://img.shields.io/badge/GraalVM-native--image-orange)](https://www.graalvm.org/)
[![License: BSD-3](https://img.shields.io/badge/License-BSD--3-green)](https://opensource.org/licenses/BSD-3-Clause)

An MCP (Model Context Protocol) server built with [Quarkus](https://quarkus.io/) that exposes common email operations as tools for LLM clients like Claude. Supports multiple named email accounts (e.g., work + Gmail) from a single server instance.

**WARNING:** This server performs real operations on your mailbox — moving, deleting, and sending emails. Always ensure you have a backup of your Maildir / emails before use. The author assumes no responsibility for any loss of data or unintended consequences resulting from the use of this MCP server. Use at your own risk.

## Features / Tools

| Tool | Description |
|------|-------------|
| **Discovery** | |
| `getVersion` | Returns the server version |
| `listAccounts` | List configured account names (call first to discover accounts) |
| `listFolders` | List all mail folders (INBOX, Sent, Drafts, etc.) |
| `listFolderTree` | Full folder hierarchy with message/unread counts |
| `createFolder` | Create a new mail folder |
| **Reading** | |
| `listEmails` | List emails in a folder with pagination |
| `readEmail` | Read the full content of a specific email |
| `getAttachment` | Download an email attachment by name. Images are returned directly; PDFs are converted to extracted text; other types as base64 blobs |
| `selfTestPdf` | Diagnostic: verify PDF text extraction works in this environment |
| `searchEmails` | Search by subject, sender, or body |
| `getUnreadCount` | Count unread emails in a folder |
| `getNextUnreadEmail` | Get oldest unread email with all headers |
| **Triage** | |
| `triageCompact` | Compact triage — from, subject, spam score, flags (start here) |
| `triageEmails` | Full-header triage — use when compact is not enough |
| **Organizing** | |
| `moveEmail` | Move a single email between folders (with optional mark-read) |
| `moveEmails` | Batch move emails to one target folder |
| `batchMoveEmails` | Move emails to multiple target folders in one call |
| `moveToSpam` | Move emails to the cached spam folder |
| `setEmailFlags` | Set any combination of seen, answered, forwarded, and flagged/starred on one or more emails |
| `deleteEmail` | Delete an email (moves to Trash by default) |
| **Spam** | |
| `getSpamFolder` | Auto-detect and cache the spam/junk folder |
| `setSpamFolder` | Manually override the spam folder |
| **Composing** | |
| `saveDraft` | Save a draft email for user review with CC/BCC (preferred over send) |
| `sendEmail` | Send an email via SMTP with CC/BCC |
| `replyEmail` | Reply with proper threading headers and optional CC/BCC |
| `forwardEmail` | Verbatim forward (body never enters LLM context) |
| `forwardEmailWithComment` | Forward with a comment prepended |

All tools (except `listAccounts`) require an `account` parameter — the name of the account to operate on (e.g., `work`, `gmail`).

## Quick Start

The fastest way to get started is to download a pre-built release from the [Releases page](https://github.com/thegreystone/mcp-email/releases/latest). Two options are available:

### Option A: Native Binary (recommended)

No Java installation required. Download the binary for your platform:

| Platform | File |
|----------|------|
| Linux x86_64 | `mcp-email-server-<version>-linux-x86_64` |
| Linux aarch64 | `mcp-email-server-<version>-linux-aarch64` |
| macOS Apple Silicon | `mcp-email-server-<version>-macos-aarch64` |
| Windows x86_64 | `mcp-email-server-<version>-windows-x86_64.exe` |

On Linux, make the binary executable: `chmod +x mcp-email-server-*-linux-*`

### Option B: Uber-jar

Runs on any platform with [Java 21+](https://adoptium.net/) installed. Download `mcp-email-server-<version>-runner.jar`.

Then configure your MCP client (see [Claude Desktop](#setting-up-with-claude-desktop) or [Claude Code](#setting-up-with-claude-code) below).

## Configuration

All credentials are passed via **environment variables** — nothing sensitive goes in source control.
The LLM never sees these variables — it only sees tool names/descriptions and the text your tools return.

Accounts are defined by convention: `EMAIL_ACCOUNTS_<NAME>_IMAP_*` and `EMAIL_ACCOUNTS_<NAME>_SMTP_*`, where `<NAME>` is the account name in uppercase.

### Per-account variables

| Variable pattern | Required | Default | Example |
|-----------------|----------|---------|---------|
| `EMAIL_ACCOUNTS_<NAME>_IMAP_HOST` | yes | | `imap.gmail.com` |
| `EMAIL_ACCOUNTS_<NAME>_IMAP_USERNAME` | yes | | `you@gmail.com` |
| `EMAIL_ACCOUNTS_<NAME>_IMAP_PASSWORD` | yes | | `abcd efgh ijkl mnop` |
| `EMAIL_ACCOUNTS_<NAME>_IMAP_PORT` | no | `993` | |
| `EMAIL_ACCOUNTS_<NAME>_IMAP_SSL` | no | `true` | |
| `EMAIL_ACCOUNTS_<NAME>_SMTP_HOST` | yes | | `smtp.gmail.com` |
| `EMAIL_ACCOUNTS_<NAME>_SMTP_USERNAME` | yes | | `you@gmail.com` |
| `EMAIL_ACCOUNTS_<NAME>_SMTP_PASSWORD` | yes | | `abcd efgh ijkl mnop` |
| `EMAIL_ACCOUNTS_<NAME>_SMTP_PORT` | no | `587` | |
| `EMAIL_ACCOUNTS_<NAME>_SMTP_STARTTLS` | no | `true` | |

For Gmail, create an [App Password](https://myaccount.google.com/apppasswords).

You can define as many accounts as needed. For example, to add a `work` and `gmail` account, set environment variables for both `EMAIL_ACCOUNTS_WORK_*` and `EMAIL_ACCOUNTS_GMAIL_*`.

## Setting up with Claude Desktop

1. Download a release (see [Quick Start](#quick-start)) or [build from source](#building-from-source).

2. Edit the Claude Desktop config file `claude_desktop_config.json`.
   On Windows it is located at `C:\Users\<UserName>\AppData\Roaming\Claude\claude_desktop_config.json`.
   On macOS it is at `~/Library/Application Support/Claude/claude_desktop_config.json`.

3. Add the `email` entry to the `mcpServers` section. Here is an example with two accounts (`work` and `gmail`):

**Using the native binary (Windows):**

```json
{
  "mcpServers": {
    "email": {
      "command": "C:\\Users\\YourName\\path\\to\\mcp-email-server-windows-x86_64.exe",
      "args": [
        "-Dquarkus.mcp.server.stdio.enabled=true",
        "-Dquarkus.config.locations=.",
        "-Duser.dir=C:\\Users\\YourName\\claude"
      ],
      "env": {
        "EMAIL_ACCOUNTS_WORK_IMAP_HOST": "imap.example.com",
        "EMAIL_ACCOUNTS_WORK_IMAP_USERNAME": "user@example.com",
        "EMAIL_ACCOUNTS_WORK_IMAP_PASSWORD": "your-app-password",
        "EMAIL_ACCOUNTS_WORK_SMTP_HOST": "smtp.example.com",
        "EMAIL_ACCOUNTS_WORK_SMTP_USERNAME": "user@example.com",
        "EMAIL_ACCOUNTS_WORK_SMTP_PASSWORD": "your-app-password",
        "EMAIL_ACCOUNTS_GMAIL_IMAP_HOST": "imap.gmail.com",
        "EMAIL_ACCOUNTS_GMAIL_IMAP_USERNAME": "you@gmail.com",
        "EMAIL_ACCOUNTS_GMAIL_IMAP_PASSWORD": "your-gmail-app-password",
        "EMAIL_ACCOUNTS_GMAIL_SMTP_HOST": "smtp.gmail.com",
        "EMAIL_ACCOUNTS_GMAIL_SMTP_USERNAME": "you@gmail.com",
        "EMAIL_ACCOUNTS_GMAIL_SMTP_PASSWORD": "your-gmail-app-password"
      }
    }
  }
}
```

**Using the uber-jar:**

```json
{
  "mcpServers": {
    "email": {
      "command": "java",
      "args": [
        "-Dquarkus.mcp.server.stdio.enabled=true",
        "-Dquarkus.config.locations=.",
        "-Duser.dir=C:\\Users\\YourName\\claude",
        "-jar",
        "C:\\Users\\YourName\\path\\to\\mcp-email-server-runner.jar"
      ],
      "env": {
        "EMAIL_ACCOUNTS_WORK_IMAP_HOST": "imap.example.com",
        "EMAIL_ACCOUNTS_WORK_IMAP_USERNAME": "user@example.com",
        "EMAIL_ACCOUNTS_WORK_IMAP_PASSWORD": "your-app-password",
        "EMAIL_ACCOUNTS_WORK_SMTP_HOST": "smtp.example.com",
        "EMAIL_ACCOUNTS_WORK_SMTP_USERNAME": "user@example.com",
        "EMAIL_ACCOUNTS_WORK_SMTP_PASSWORD": "your-app-password",
        "EMAIL_ACCOUNTS_GMAIL_IMAP_HOST": "imap.gmail.com",
        "EMAIL_ACCOUNTS_GMAIL_IMAP_USERNAME": "you@gmail.com",
        "EMAIL_ACCOUNTS_GMAIL_IMAP_PASSWORD": "your-gmail-app-password",
        "EMAIL_ACCOUNTS_GMAIL_SMTP_HOST": "smtp.gmail.com",
        "EMAIL_ACCOUNTS_GMAIL_SMTP_USERNAME": "you@gmail.com",
        "EMAIL_ACCOUNTS_GMAIL_SMTP_PASSWORD": "your-gmail-app-password"
      }
    }
  }
}
```

4. Restart Claude Desktop. The email tools should appear in the tool list.

## Setting up with Claude Code

1. Download a release (see [Quick Start](#quick-start)) or [build from source](#building-from-source).

2. Edit `~/.claude.json` and add an `mcpServers` section. Here is an example with two accounts (`work` and `gmail`):

**Using the native binary (Linux):**

```json
{
  "mcpServers": {
    "email": {
      "command": "/path/to/mcp-email-server-linux-x86_64",
      "args": [
        "-Dquarkus.mcp.server.stdio.enabled=true"
      ],
      "env": {
        "EMAIL_ACCOUNTS_WORK_IMAP_HOST": "imap.example.com",
        "EMAIL_ACCOUNTS_WORK_IMAP_USERNAME": "user@example.com",
        "EMAIL_ACCOUNTS_WORK_IMAP_PASSWORD": "your-app-password",
        "EMAIL_ACCOUNTS_WORK_SMTP_HOST": "smtp.example.com",
        "EMAIL_ACCOUNTS_WORK_SMTP_USERNAME": "user@example.com",
        "EMAIL_ACCOUNTS_WORK_SMTP_PASSWORD": "your-app-password",
        "EMAIL_ACCOUNTS_GMAIL_IMAP_HOST": "imap.gmail.com",
        "EMAIL_ACCOUNTS_GMAIL_IMAP_USERNAME": "you@gmail.com",
        "EMAIL_ACCOUNTS_GMAIL_IMAP_PASSWORD": "your-gmail-app-password",
        "EMAIL_ACCOUNTS_GMAIL_SMTP_HOST": "smtp.gmail.com",
        "EMAIL_ACCOUNTS_GMAIL_SMTP_USERNAME": "you@gmail.com",
        "EMAIL_ACCOUNTS_GMAIL_SMTP_PASSWORD": "your-gmail-app-password"
      }
    }
  }
}
```

**Using the uber-jar:**

```json
{
  "mcpServers": {
    "email": {
      "command": "java",
      "args": [
        "-Dquarkus.mcp.server.stdio.enabled=true",
        "-jar",
        "/path/to/mcp-email-server-runner.jar"
      ],
      "env": {
        "EMAIL_ACCOUNTS_WORK_IMAP_HOST": "imap.example.com",
        "EMAIL_ACCOUNTS_WORK_IMAP_USERNAME": "user@example.com",
        "EMAIL_ACCOUNTS_WORK_IMAP_PASSWORD": "your-app-password",
        "EMAIL_ACCOUNTS_WORK_SMTP_HOST": "smtp.example.com",
        "EMAIL_ACCOUNTS_WORK_SMTP_USERNAME": "user@example.com",
        "EMAIL_ACCOUNTS_WORK_SMTP_PASSWORD": "your-app-password",
        "EMAIL_ACCOUNTS_GMAIL_IMAP_HOST": "imap.gmail.com",
        "EMAIL_ACCOUNTS_GMAIL_IMAP_USERNAME": "you@gmail.com",
        "EMAIL_ACCOUNTS_GMAIL_IMAP_PASSWORD": "your-gmail-app-password",
        "EMAIL_ACCOUNTS_GMAIL_SMTP_HOST": "smtp.gmail.com",
        "EMAIL_ACCOUNTS_GMAIL_SMTP_USERNAME": "you@gmail.com",
        "EMAIL_ACCOUNTS_GMAIL_SMTP_PASSWORD": "your-gmail-app-password"
      }
    }
  }
}
```

3. Restart Claude Code (or run `/mcp` to reconnect). The email tools should appear in the tool list.

**Note:** Unlike Claude Desktop, Claude Code does not typically need the `-Duser.dir` and `-Dquarkus.config.locations` workarounds since it launches the server from a normal working directory.

## Building from Source

Only needed if you want to contribute or run a development build.

### Uber-jar

**Prerequisites:** Java 21+ and Maven 3.9+

```bash
mvn package
```

The uber-jar will be at `target/mcp-email-server-<version>-runner.jar`.

### Native image

**Prerequisites:** [GraalVM 21+](https://www.graalvm.org/downloads/) with `native-image`, and Maven 3.9+. On Windows, Visual Studio 2022 with the "Desktop development with C++" workload is also required.

```bash
mvn package -Dnative -DskipTests
```

The native binary will be at `target/mcp-email-server-<version>-runner` (or `.exe` on Windows).

## Troubleshooting

- **`AccessDeniedException: C:\WINDOWS\system32\config`**: on Windows, Claude Desktop may launch the server with `C:\WINDOWS\system32` as the working directory, causing Quarkus to fail when scanning for config files. The `-Duser.dir` argument in the example config overrides the working directory, and `-Dquarkus.config.locations=.` prevents Quarkus from scanning restricted system directories. Point `-Duser.dir` to any directory your user can write to.
- **Server disconnects immediately**: make sure `-Dquarkus.mcp.server.stdio.enabled=true` is in the `args` before `-jar`.
- **No log output visible**: Quarkus logs go to `mcp-email-server.log` (in the working directory), not to stdout/stderr, to avoid interfering with the STDIO transport. Check that file for errors.
- **Authentication errors**: for Gmail, you need an [App Password](https://myaccount.google.com/apppasswords), not your regular password. Make sure 2-Step Verification is enabled on your Google account first.
- **Build fails** (building from source): ensure `JAVA_HOME` points to JDK 21+. The system `java` on PATH may differ from what Maven uses.
- **"Unknown account" errors**: call `listAccounts` first to see which accounts are configured. Account names are lowercase as defined in the environment variables (e.g., `work`, `gmail`).
