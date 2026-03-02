# MCP Email Server

An MCP (Model Context Protocol) server built with [Quarkus](https://quarkus.io/) that exposes common email operations as tools for LLM clients like Claude. Supports multiple named email accounts (e.g., work + Gmail) from a single server instance.

**WARNING:** This server performs real operations on your mailbox — moving, deleting, and sending emails. Always ensure you have a backup of your Maildir / emails before use. The author assumes no responsibility for any loss of data or unintended consequences resulting from the use of this MCP server. Use at your own risk.

## Features / Tools

| Tool | Description |
|------|-------------|
| `listAccounts` | List configured account names (call first to discover accounts) |
| `listFolders` | List all mail folders (INBOX, Sent, Drafts, etc.) |
| `listFolderTree` | Full folder hierarchy with message/unread counts |
| `listEmails` | List emails in a folder with pagination |
| `readEmail` | Read the full content of a specific email |
| `getNextUnreadEmail` | Get oldest unread email with all headers (for spam triage) |
| `triageUnread` | Quick-scan unread emails — headers only, no body |
| `getUnreadCount` | Count unread emails in a folder |
| `getSpamFolder` | Auto-detect and cache the spam/junk folder |
| `setSpamFolder` | Manually override the spam folder |
| `moveToSpam` | Move an email to the cached spam folder |
| `moveEmail` | Move an email between folders |
| `moveEmails` | Batch move multiple emails in one operation |
| `deleteEmail` | Delete an email (moves to Trash by default) |
| `searchEmails` | Search by subject, sender, or body |
| `sendEmail` | Send an email via SMTP |
| `replyEmail` | Reply to an email with proper threading headers |
| `forwardEmail` | Verbatim forward (body never enters LLM context) |
| `forwardEmailWithComment` | Forward with a comment prepended |
| `markEmail` | Mark an email as read/unread |
| `markEmails` | Batch mark multiple emails as read/unread |
| `flagEmail` | Flag/star emails (IMAP `\Flagged`) with optional follow-up date |
| `saveDraft` | Save a draft email for user review |
| `createFolder` | Create a new mail folder |

All tools (except `listAccounts`) require an `account` parameter — the name of the account to operate on (e.g., `work`, `gmail`).

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

1. Build the uber-jar (see above).

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
        "-jar",
        "C:\\Users\\YourName\\path\\to\\mcp-email-server-1.0.0-SNAPSHOT-runner.jar"
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

## Troubleshooting

- **Server disconnects immediately**: make sure `-Dquarkus.mcp.server.stdio.enabled=true` is in the `args` before `-jar`.
- **No log output visible**: Quarkus logs go to `mcp-email-server.log` (in the working directory), not to stdout/stderr, to avoid interfering with the STDIO transport. Check that file for errors.
- **Authentication errors**: for Gmail, you need an [App Password](https://myaccount.google.com/apppasswords), not your regular password. Make sure 2-Step Verification is enabled on your Google account first.
- **Build fails**: ensure `JAVA_HOME` points to JDK 21+. The system `java` on PATH may differ from what Maven uses.
- **"Unknown account" errors**: call `listAccounts` first to see which accounts are configured. Account names are lowercase as defined in the environment variables (e.g., `work`, `gmail`).
