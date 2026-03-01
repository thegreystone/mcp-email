# MCP Email Server

An MCP (Model Context Protocol) server built with [Quarkus](https://quarkus.io/) that exposes common email operations as tools for LLM clients like Claude.

**WARNING:** This server performs real operations on your mailbox — moving, deleting, and sending emails. Always ensure you have a backup of your Maildir / emails before use. The author assumes no responsibility for any loss of data or unintended consequences resulting from the use of this MCP server. Use at your own risk.

## Features / Tools

| Tool | Description |
|------|-------------|
| `listFolders` | List all mail folders (INBOX, Sent, Drafts, etc.) |
| `listFolderTree` | Full folder hierarchy with message/unread counts |
| `listEmails` | List emails in a folder with pagination |
| `readEmail` | Read the full content of a specific email |
| `getNextUnreadEmail` | Get oldest unread email with all headers (for spam triage) |
| `getUnreadCount` | Count unread emails in a folder |
| `getSpamFolder` | Auto-detect and cache the spam/junk folder |
| `setSpamFolder` | Manually override the spam folder |
| `moveToSpam` | Move an email to the cached spam folder |
| `moveEmail` | Move an email between folders |
| `deleteEmail` | Delete an email (moves to Trash by default) |
| `searchEmails` | Search by subject, sender, or body |
| `sendEmail` | Send an email via SMTP |
| `markEmail` | Mark an email as read/unread |

## Prerequisites

- Java 21+ (tested with JDK 25)
- Maven 3.9+ (only needed for building)

## Build

```bash
# Build the uber-jar
mvn package -DskipTests

# The artifact will be at:
# target/mcp-email-server-1.0.0-SNAPSHOT-runner.jar
```

Note: Maven must use Java 21+. If your system Maven defaults to an older JDK, set `JAVA_HOME` before building:

```bash
mvn package -DskipTests
```

## Configuration

All credentials are passed via **environment variables** — nothing sensitive goes in source control.
The LLM never sees these variables — it only sees tool names/descriptions and the text your tools return.

| Variable | Required | Default | Example |
|----------|----------|---------|---------|
| `EMAIL_IMAP_HOST` | yes | | `imap.gmail.com` |
| `EMAIL_IMAP_USERNAME` | yes | | `you@gmail.com` |
| `EMAIL_IMAP_PASSWORD` | yes | | `abcd efgh ijkl mnop` |
| `EMAIL_IMAP_PORT` | no | `993` | |
| `EMAIL_IMAP_SSL` | no | `true` | |
| `EMAIL_SMTP_HOST` | yes | | `smtp.gmail.com` |
| `EMAIL_SMTP_USERNAME` | yes | | `you@gmail.com` |
| `EMAIL_SMTP_PASSWORD` | yes | | `abcd efgh ijkl mnop` |
| `EMAIL_SMTP_PORT` | no | `587` | |
| `EMAIL_SMTP_STARTTLS` | no | `true` | |

For Gmail, create an [App Password](https://myaccount.google.com/apppasswords).

## Setting up with Claude Desktop

1. Build the uber-jar (see above).

2. Edit the Claude Desktop config file `claude_desktop_config.json`.
   On Windows it is located at `C:\Users\<UserName>\AppData\Roaming\Claude\claude_desktop_config.json`.
   On macOS it is at `~/Library/Application Support/Claude/claude_desktop_config.json`.

3. Add the `email` entry to the `mcpServers` section:

```json
{
  "mcpServers": {
    "email": {
      "command": "java",
      "args": [
        "-Dquarkus.mcp.server.stdio.enabled=true",
        "-jar",
        "C:\\Users\\YourName\\path\\to\\mcp-email-server-1.0.0-SNAPSHOT-runner.jar"
      ],
      "env": {
        "EMAIL_IMAP_HOST": "imap.gmail.com",
        "EMAIL_IMAP_PORT": "993",
        "EMAIL_IMAP_SSL": "true",
        "EMAIL_IMAP_USERNAME": "you@gmail.com",
        "EMAIL_IMAP_PASSWORD": "your-app-password",
        "EMAIL_SMTP_HOST": "smtp.gmail.com",
        "EMAIL_SMTP_PORT": "587",
        "EMAIL_SMTP_STARTTLS": "true",
        "EMAIL_SMTP_USERNAME": "you@gmail.com",
        "EMAIL_SMTP_PASSWORD": "your-app-password"
      }
    }
  }
}
```

4. Restart Claude Desktop. The email tools should appear in the tool list.

## Setting up with Claude Code

Add the same config to your Claude Code MCP settings (`~/.claude/settings.json` or project-level `.claude/settings.json`).

## Troubleshooting

- **Server disconnects immediately**: make sure `-Dquarkus.mcp.server.stdio.enabled=true` is in the `args` before `-jar`.
- **No log output visible**: Quarkus logs go to `mcp-email-server.log` (in the working directory), not to stdout/stderr, to avoid interfering with the STDIO transport. Check that file for errors.
- **Authentication errors**: for Gmail, you need an [App Password](https://myaccount.google.com/apppasswords), not your regular password. Make sure 2-Step Verification is enabled on your Google account first.
- **Build fails**: ensure `JAVA_HOME` points to JDK 21+. The system `java` on PATH may differ from what Maven uses.
