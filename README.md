# MCP Email Server

[![Build](https://github.com/thegreystone/mcp-email/actions/workflows/build.yml/badge.svg)](https://github.com/thegreystone/mcp-email/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/thegreystone/mcp-email)](https://github.com/thegreystone/mcp-email/releases/latest)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](https://adoptium.net/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.21-blueviolet)](https://quarkus.io/)
[![License: BSD-3](https://img.shields.io/badge/License-BSD--3-green)](https://opensource.org/licenses/BSD-3-Clause)

An MCP (Model Context Protocol) server built with [Quarkus](https://quarkus.io/) that exposes common email operations as tools for LLM clients like Claude. Supports multiple named email accounts (e.g., work + Gmail) from a single server instance.

**WARNING:** This server performs real operations on your mailbox — moving, deleting, and sending emails. Always ensure you have a backup of your Maildir / emails before use. The author assumes no responsibility for any loss of data or unintended consequences resulting from the use of this MCP server. Use at your own risk.

## Features / Tools

| Tool | Description |
|------|-------------|
| **Discovery** | |
| `listAccounts` | List configured account names (call first to discover accounts) |
| `listFolders` | List all mail folders (INBOX, Sent, Drafts, etc.) |
| `listFolderTree` | Full folder hierarchy with message/unread counts |
| `createFolder` | Create a new mail folder |
| **Reading** | |
| `listEmails` | List emails in a folder with pagination |
| `readEmail` | Read the full content of a specific email |
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
| `markEmail` | Mark a single email as read/unread |
| `markEmails` | Batch mark multiple emails as read/unread |
| `flagEmail` | Flag/star emails (IMAP `\Flagged`) with optional follow-up date |
| `setEmailFlags` | Correct answered/forwarded flags on an email |
| `deleteEmail` | Delete an email (moves to Trash by default) |
| **Spam** | |
| `getSpamFolder` | Auto-detect and cache the spam/junk folder |
| `setSpamFolder` | Manually override the spam folder |
| **Composing** | |
| `saveDraft` | Save a draft email for user review (preferred over send) |
| `sendEmail` | Send an email via SMTP |
| `replyEmail` | Reply to an email with proper threading headers |
| `forwardEmail` | Verbatim forward (body never enters LLM context) |
| `forwardEmailWithComment` | Forward with a comment prepended |

All tools (except `listAccounts`) require an `account` parameter — the name of the account to operate on (e.g., `work`, `gmail`).

## Quick Start

The fastest way to get started is to download a pre-built release:

1. Install [Java 21+](https://adoptium.net/) if you don't already have it.
2. Download the latest `mcp-email-server-<version>-runner.jar` from the [Releases page](https://github.com/thegreystone/mcp-email/releases/latest).
3. Configure your MCP client (see [Claude Desktop](#setting-up-with-claude-desktop) or [Claude Code](#setting-up-with-claude-code) below).

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

1. Download the jar (see [Quick Start](#quick-start)) or [build from source](#building-from-source).

2. Edit the Claude Desktop config file `claude_desktop_config.json`.
   On Windows it is located at `C:\Users\<UserName>\AppData\Roaming\Claude\claude_desktop_config.json`.
   On macOS it is at `~/Library/Application Support/Claude/claude_desktop_config.json`.

3. Add the `email` entry to the `mcpServers` section. Here is an example with two accounts (`work` and `gmail`):

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
        "C:\\Users\\YourName\\path\\to\\mcp-email-server-1.0.1-runner.jar"
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

Add the same config to your Claude Code MCP settings (`~/.claude/settings.json` or project-level `.claude/settings.json`).

## Building from Source

Only needed if you want to contribute or run a development build.

**Prerequisites:** Java 21+ and Maven 3.9+

```bash
mvn package
```

The uber-jar will be at `target/mcp-email-server-<version>-runner.jar`.

## Troubleshooting

- **`AccessDeniedException: C:\WINDOWS\system32\config`**: on Windows, Claude Desktop may launch the server with `C:\WINDOWS\system32` as the working directory, causing Quarkus to fail when scanning for config files. The `-Duser.dir` argument in the example config overrides the working directory, and `-Dquarkus.config.locations=.` prevents Quarkus from scanning restricted system directories. Point `-Duser.dir` to any directory your user can write to.
- **Server disconnects immediately**: make sure `-Dquarkus.mcp.server.stdio.enabled=true` is in the `args` before `-jar`.
- **No log output visible**: Quarkus logs go to `mcp-email-server.log` (in the working directory), not to stdout/stderr, to avoid interfering with the STDIO transport. Check that file for errors.
- **Authentication errors**: for Gmail, you need an [App Password](https://myaccount.google.com/apppasswords), not your regular password. Make sure 2-Step Verification is enabled on your Google account first.
- **Build fails** (building from source): ensure `JAVA_HOME` points to JDK 21+. The system `java` on PATH may differ from what Maven uses.
- **"Unknown account" errors**: call `listAccounts` first to see which accounts are configured. Account names are lowercase as defined in the environment variables (e.g., `work`, `gmail`).
