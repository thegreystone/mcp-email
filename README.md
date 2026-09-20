# Email MCP Server

[![Build](https://github.com/thegreystone/mcp-email/actions/workflows/build.yml/badge.svg)](https://github.com/thegreystone/mcp-email/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/thegreystone/mcp-email)](https://github.com/thegreystone/mcp-email/releases/latest)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](https://adoptium.net/)
[![Quarkus](https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fthegreystone%2Fmcp-email%2Fmaster%2Fpom.xml&query=%2F%2F*%5Blocal-name()%3D'quarkus.platform.version'%5D&label=Quarkus&color=blueviolet)](https://quarkus.io/)
[![GraalVM Native](https://img.shields.io/badge/GraalVM-native--image-orange)](https://www.graalvm.org/)
[![License: BSD-3](https://img.shields.io/badge/License-BSD--3-green)](https://opensource.org/licenses/BSD-3-Clause)

An MCP (Model Context Protocol) server that exposes common email operations as tools for LLM clients like Claude. Supports multiple named email accounts (e.g., work + Gmail) from a single server instance.

For more information, see my [blog](https://hirt.se/blog/?p=1596).

### Example prompts

> "Find the tax emails from my tax lawyers for 2025, and check the content and attachments to fully understand what documentation I need to provide. Please provide an action plan to get that documentation."

> "Please help triage the emails in my inboxes. As per usual, flag anything that is actionable. Don't hesitate to read emails that you are uncertain about. For anything actionable, provide an action plan. For anything non-actionable, file according to the folder layout."

**WARNING:** This server performs real operations on your mailbox, for example moving emails around. By default it will not allow deletions and directly sending emails (this can be changed in the MCP config). The author assumes no responsibility for any loss of data or unintended consequences resulting from the use of this MCP server. Use at your own risk.

**NOTE:** That said, the author has been using this daily for weeks without issues.

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
| `listEmails` | List emails in a folder with pagination. Sortable by date (default), size, or sender |
| `readEmail` | Read the full content of a specific email. HTML bodies are converted to markdown by default |
| `getAttachment` | Download an email attachment by name. Images are returned directly; PDFs are converted to extracted text; other types as base64 blobs |
| `selfTestPdf` | Diagnostic: verify PDF text extraction works in this environment |
| `searchEmails` | Search by subject, sender, or body |
| `getUnreadCount` | Count unread emails in a folder |
| `getNextUnreadEmail` | Get oldest unread email with all headers. HTML bodies are converted to markdown |
| **Triage** | |
| `triageCompact` | Compact triage — from, subject, spam score, flags (start here) |
| `triageEmails` | Full-header triage — use when compact is not enough |
| **Organizing** | |
| `moveEmail` | Move a single email between folders (with optional mark-read) |
| `moveEmails` | Batch move emails to one target folder |
| `batchMoveEmails` | Move emails to multiple target folders in one call |
| `moveToSpam` | Move emails to the cached spam folder |
| `setEmailFlags` | Set any combination of seen, answered, forwarded, and flagged/starred on one or more emails |
| `deleteEmail` | Delete an email: moved to the trash folder, or removed permanently if the account has none. **Disabled by default** — opt in via `EMAIL_ALLOW_DELETION=true` (see [Enabling permanent deletion](#enabling-permanent-deletion)) |
| `getTrashFolder` | Resolve and cache the trash folder that `deleteEmail` moves messages to (see [Special folders](#special-folders)) |
| `setTrashFolder` | Override the trash folder for this session |
| **Spam** | |
| `getSpamFolder` | Resolve and cache the spam/junk folder (configured, special-use flag, or well-known name; see [Special folders](#special-folders)) |
| `setSpamFolder` | Override the spam folder for this session |
| **Composing** | |
| `getDraftsFolder` | Resolve and cache the Drafts folder that `saveDraft` writes to (see [Special folders](#special-folders)) |
| `setDraftsFolder` | Override the Drafts folder for this session |
| `saveDraft` | Save a draft email for user review with CC/BCC (always available; preferred over send) |
| `sendEmail` | Send an email via SMTP with CC/BCC. **Disabled by default** — opt in via `EMAIL_ALLOW_SENDING=true` (see [Enabling outbound sending](#enabling-outbound-sending)) |
| `replyEmail` | Reply with proper threading headers and optional CC/BCC. **Disabled by default** — opt in via `EMAIL_ALLOW_SENDING=true` |
| `forwardEmail` | Verbatim forward (body never enters LLM context). **Disabled by default** — opt in via `EMAIL_ALLOW_SENDING=true` |
| `forwardEmailWithComment` | Forward with a comment prepended. **Disabled by default** — opt in via `EMAIL_ALLOW_SENDING=true` |

All tools (except `listAccounts`) require an `account` parameter — the name of the account to operate on (e.g., `work`, `gmail`).

## Quick Start

The fastest way to get started is to download a pre-built release from the [Releases page](https://github.com/thegreystone/mcp-email/releases/latest). Two options are available:

**Claude Desktop users:** the simplest install is the MCP Bundle, `mcp-email-server-<version>-macos-aarch64.mcpb`
or `mcp-email-server-<version>-windows-x86_64.mcpb`, see [Setting up with Claude Desktop](#setting-up-with-claude-desktop).

### Option A: Native Binary (recommended)

No Java installation required. Download the binary for your platform:

| Platform | File |
|----------|------|
| Linux x86_64 | `mcp-email-server-<version>-linux-x86_64` |
| Linux aarch64 | `mcp-email-server-<version>-linux-aarch64` |
| macOS Apple Silicon | `mcp-email-server-<version>-macos-aarch64` |
| Windows x86_64 | `mcp-email-server-<version>-windows-x86_64.exe` |

On Linux and macOS, make the binary executable: `chmod +x mcp-email-server-*`

The macOS binary is signed and notarized, so Gatekeeper accepts it as downloaded. The one exception is a
first launch while offline, because Gatekeeper fetches the notarization ticket from Apple; if that
happens, clear the quarantine flag once:

```bash
xattr -d com.apple.quarantine mcp-email-server-<version>-macos-aarch64
```

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
| `EMAIL_ACCOUNTS_<NAME>_IMAP_PORT` | no | `993` | `143` |
| `EMAIL_ACCOUNTS_<NAME>_IMAP_SSL` | no | `true`, or `false` on port 143 | |
| `EMAIL_ACCOUNTS_<NAME>_SMTP_HOST` | yes | | `smtp.gmail.com` |
| `EMAIL_ACCOUNTS_<NAME>_SMTP_USERNAME` | yes | | `you@gmail.com` |
| `EMAIL_ACCOUNTS_<NAME>_SMTP_PASSWORD` | yes | | `abcd efgh ijkl mnop` |
| `EMAIL_ACCOUNTS_<NAME>_SMTP_PORT` | no | `587` | `465` |
| `EMAIL_ACCOUNTS_<NAME>_SMTP_STARTTLS` | no | `true` | |
| `EMAIL_ACCOUNTS_<NAME>_SMTP_SSL` | no | `false`, or `true` on port 465 | |
| `EMAIL_ACCOUNTS_<NAME>_FROM` | no | the SMTP username | `Jane Doe <jane@example.com>` |
| `EMAIL_ACCOUNTS_<NAME>_DRAFTS_FOLDER` | no | auto-detected | `INBOX.INBOX.Drafts` |
| `EMAIL_ACCOUNTS_<NAME>_SPAM_FOLDER` | no | auto-detected | `INBOX.INBOX.Junk` |
| `EMAIL_ACCOUNTS_<NAME>_TRASH_FOLDER` | no | auto-detected | `INBOX.INBOX.Trash` |

For Gmail, create an [App Password](https://myaccount.google.com/apppasswords).

`FROM` is the From address on everything the account sends or drafts, as a bare address or as
`Display Name <address>`. It defaults to the SMTP username, which is the address itself for most providers;
set it when the SMTP login is not an email address, to send from an alias, or to add a display name. Reply-all
leaves the account's own addresses (the From address and the usernames) out of the recipients.

You can define as many accounts as needed. For example, to add a `work` and `gmail` account, set environment
variables for both `EMAIL_ACCOUNTS_WORK_*` and `EMAIL_ACCOUNTS_GMAIL_*`.

### Transport security

With the default ports, IMAP is TLS on 993 and SMTP is port 587 upgraded with STARTTLS; the STARTTLS upgrade
is required, so a server that does not offer it is refused rather than sent the password in the clear. The
two other common setups need only the port, since the TLS mode follows it:

- **SMTP on port 465 (implicit TLS, "SMTPS")**, which some providers offer instead of 587: set
  `EMAIL_ACCOUNTS_<NAME>_SMTP_PORT=465`. The connection is then encrypted from the first byte and the
  STARTTLS setting is ignored.
- **Plain IMAP on port 143**: set `EMAIL_ACCOUNTS_<NAME>_IMAP_PORT=143`. The connection is upgraded with
  STARTTLS when the server offers it, and stays unencrypted otherwise (for a local or test server).

For a non-standard port, set `IMAP_SSL` or `SMTP_SSL` explicitly; an explicit value always wins over the
port rule. Set `SMTP_STARTTLS=false` only for a server that really has no TLS at all.

### Special folders

`saveDraft`, `moveToSpam` and `deleteEmail` need to know the account's Drafts, spam and trash folders. Most
servers need no configuration; each folder is resolved on first use, in this order:

1. A folder set for the current session with `setDraftsFolder` / `setSpamFolder` / `setTrashFolder`.
2. The configured `EMAIL_ACCOUNTS_<NAME>_DRAFTS_FOLDER` / `_SPAM_FOLDER` / `_TRASH_FOLDER`. A configured
   folder that does not exist on the server is reported as an error rather than silently falling back, so
   the LLM can point out the misconfiguration and work around it with the `set*Folder` tool for the session.
3. The folder the server flags as `\Drafts` / `\Junk` / `\Trash` (RFC 6154 special-use, supported by Gmail,
   Dovecot, Exchange and most others).
4. Well-known names such as `Drafts`, `[Gmail]/Drafts`, `INBOX.Drafts`, `Spam`, `Junk`, `[Gmail]/Spam`,
   `Trash`, `[Gmail]/Trash`, `Deleted Items`.
5. Any folder whose last path element is `Drafts`, `Draft`, `Spam`, `Junk`, `Junk E-mail`, `Bulk Mail`,
   `Junk Email`, `Trash`, `Deleted Items`, `Deleted Messages` or a few localized trash names, which covers
   providers that nest everything under a namespace prefix (e.g. OVH's `INBOX.INBOX.Drafts`).

Set the variable explicitly if your provider uses a name none of the heuristics find, or if auto-detection
picks the wrong folder. `deleteEmail` removes the message permanently only when no trash folder is found.

### Network timeout

One setting covers every connection of every account:

| Variable | Default | Effect |
|----------|---------|--------|
| `EMAIL_NETWORK_TIMEOUT` | `60` | Seconds to wait when connecting to an IMAP or SMTP server, for the server to answer, and for a write to complete. |

Without it a server that stops answering would hang the tool call, and with it the conversation, for good.
The wait is per socket read, not per operation, so a large attachment over a slow link is fine as long as
data keeps arriving. Raise it if a slow server times out on big searches; `0` disables the timeouts.
Equivalent system property: `-Demail.network-timeout=60`.

### Safety flags — opt-in for irreversible actions

Permanent and outbound operations are **opt-in** for safety. By default the destructive/outbound tools are disabled and return an explanatory error so the LLM can tell the user what's happening and fall back to a safer alternative (`moveEmail` to a Trash folder, or `saveDraft` for the user to send manually).

| Variable | Default | Effect |
|----------|---------|--------|
| `EMAIL_ALLOW_DELETION` | `false` | Set to `true` to allow `deleteEmail` to actually delete messages. |
| `EMAIL_ALLOW_SENDING` | `false` | Set to `true` to allow `sendEmail`, `replyEmail`, `forwardEmail`, and `forwardEmailWithComment` to actually transmit messages over SMTP. |

Equivalent system properties: `-Demail.allow-deletion=true` and `-Demail.allow-sending=true`. Restart the server after changing them.

#### Enabling permanent deletion

When `EMAIL_ALLOW_DELETION` is unset (or `false`), `deleteEmail` is still listed (so the LLM can discover it and explain the limitation), but every invocation is rejected before any IMAP call is made. Use `moveEmail` to a Trash folder as the safe alternative.

#### Enabling outbound sending

When `EMAIL_ALLOW_SENDING` is unset (or `false`), the four outbound tools above all return an explanatory error and do nothing. **`saveDraft` is unaffected** — it only writes to the IMAP Drafts folder (see [Special folders](#special-folders)), so the LLM can still compose messages for the user to review and send manually from their mail client. This is the recommended default workflow even when sending is enabled.

## Setting up with Claude Desktop

The easiest way is the MCP Bundle. There is no config file to edit:

1. Download the `.mcpb` file for your platform from the
   [Releases page](https://github.com/thegreystone/mcp-email/releases/latest):
   `mcp-email-server-<version>-macos-aarch64.mcpb` or `mcp-email-server-<version>-windows-x86_64.mcpb`.

2. Install it: double-click the file, or in Claude Desktop open *Settings → Extensions → Advanced settings →
   Install Extension…* and pick the file. Claude Desktop shows its standard unsigned-extension notice, because
   the bundle file itself carries no signature (the binary inside it is signed); confirm with *Install*.

3. Installing does not ask for the account details, so open the extension's settings: in
   *Settings → Extensions*, find *Email MCP Server* and click *Configure*. Fill in the IMAP and SMTP server,
   username and password (passwords go to the operating system keychain). For Gmail, use an
   [App Password](https://myaccount.google.com/apppasswords). The optional fields can usually stay empty:
   *From* only if the SMTP username is not your email address or you want a display name, and the
   *Drafts*, *Spam* and *Trash folder* fields only for providers where auto-detection picks the wrong folder
   (see [Special folders](#special-folders)). Leave the two switches, *Allow sending* and
   *Allow permanent deletion*, off unless you need them. Save.

4. Make sure the extension is enabled and start a new chat. The email tools appear in the tool list; the
   account is called `default` in the tools.

The bundle contains the same native binary as the standalone download, signed and notarized on macOS and
Authenticode-signed on Windows. It configures a single account with the default ports (993 with SSL for IMAP,
587 with STARTTLS for SMTP); for several accounts, or other ports and SSL settings, use the manual route below.

If you prefer the manual route:

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

**Prerequisites:** [GraalVM 25](https://www.graalvm.org/downloads/) with `native-image`, and Maven 3.9+. On Windows, Visual Studio 2022 with the "Desktop development with C++" workload is also required.

```bash
mvn package -Dnative -DskipTests
```

The native binary will be at `target/mcp-email-server-<version>-runner` (or `.exe` on Windows).

### MCP Bundles

Pushing a `v<version>` tag runs the release workflow, which builds the uber-jar and the four native images and
packs an [MCP Bundle](https://github.com/anthropics/mcpb) (`.mcpb`) for macOS and Windows, the two platforms
that have Claude Desktop. A bundle is the binary under `server/` plus a `manifest.json` filled in from
[`mcpb/manifest.json`](mcpb/manifest.json) (`__VERSION__`, `__BINARY__` and `__PLATFORM__` are substituted).
The manifest declares one account's IMAP and SMTP settings, the optional Drafts and spam folders and the two
safety switches as `user_config`, mapped to the `EMAIL_ACCOUNTS_DEFAULT_*`, `EMAIL_ALLOW_SENDING` and
`EMAIL_ALLOW_DELETION` environment variables, and passes the same `-Duser.dir` and
`-Dquarkus.config.locations` arguments as the Claude Desktop example above, with the home directory as
working directory. The packing is [`mcpb/pack.sh`](mcpb/pack.sh), run on the runner that built the binary so the executable bit survives on macOS.

The release also packs a **universal** bundle, `mcp-email-server-<version>-universal.mcpb`, with the macOS
and Windows binaries and a manifest whose `platform_overrides` pick one per operating system
([`mcpb/manifest-universal.json`](mcpb/manifest-universal.json)). It is for the cases where one file has to
serve both platforms; direct downloads should keep using the per-platform bundles, which are smaller.
Linux is left out, since Claude Desktop does not run there and it keeps the bundle around 35 MB; Linux users
take the standalone binary. [`mcpb/pack-universal.sh`](mcpb/pack-universal.sh) runs on Linux only, since a
pack done on Windows cannot set the executable bit of the macOS binary.

The manual **Bundles** workflow ([`.github/workflows/bundle.yml`](.github/workflows/bundle.yml)) builds the
bundles for an already published release from its binaries and attaches them: run it from the Actions tab
with the version, choosing *all*, *macos-only* or *universal*.

To try a bundle locally (Git Bash on Windows):

```bash
bash mcpb/pack.sh 0.0.0 windows-x86_64 win32 target/mcp-email-server-*-runner.exe target/mcp-email-server-dev.mcpb
```

### Signing the macOS binary

The macOS job signs the binary with the Apple Developer ID certificate (hardened runtime, timestamped) and
submits it to Apple's notary service before packing the bundle, following the same recipe as
[thegreystone/rpg-mcp](https://github.com/thegreystone/rpg-mcp) and
[thegreystone/diskspace](https://github.com/thegreystone/diskspace). A bare executable cannot be stapled, so
Gatekeeper fetches the notarization ticket from Apple on first run; the Quick Start keeps the `xattr` note for
offline first runs. No entitlements are needed: the native image does not JIT and loads no unsigned dylibs.
The job fails, and no release is created, if the secrets are missing. They are the same six as in rpg-mcp and
diskspace and can be copied from there:

| Secret                    | Value                                                                 |
|---------------------------|-----------------------------------------------------------------------|
| `MACOS_CERTIFICATE`       | Developer ID Application certificate + key as a base64-encoded `.p12` |
| `MACOS_CERTIFICATE_PWD`   | Password of that `.p12`                                               |
| `MACOS_SIGNING_IDENTITY`  | `Developer ID Application: <name> (<team id>)`                        |
| `NOTARIZATION_APPLE_ID`   | Apple ID used for notarization                                        |
| `NOTARIZATION_PASSWORD`   | App-specific password for that Apple ID                               |
| `NOTARIZATION_TEAM_ID`    | The 10-character team ID                                              |

### Signing the Windows binary

The Windows binary is Authenticode-signed with a Certum code-signing certificate through SimplySign Desktop.
SimplySign needs an interactive login (OTP from the mobile app), so signing is a manual step *after* the
workflow has published the release. With SimplySign Desktop running and logged in, and `gh` authenticated:

```powershell
.\mcpb\Sign-Release.ps1 -Version 1.0.12            # add -NoUpload to inspect target\signed first
```

[`mcpb/Sign-Release.ps1`](mcpb/Sign-Release.ps1) downloads the Windows binary from the release, signs it
(SHA-256, Certum RFC 3161 timestamp), verifies signature and timestamp, re-packs the Windows `.mcpb` around
the signed binary, replaces both assets on the release and dispatches the Bundles workflow to re-pack the
universal bundle. The bundle manifest carries no file hashes, so a re-pack around a signed binary is a valid
bundle. The script reads the certificate thumbprint from `$env:CERTUM_SIGN_THUMBPRINT` (or
`$env:DISKSPACE_SIGN_THUMBPRINT`, the same certificate) so that renewal is a one-line edit in `$PROFILE`, not
a commit. Always pin by thumbprint; `signtool /a` can pick the wrong certificate silently.

What is *not* signed: the `.mcpb` container itself (`mcpb sign` needs the private key as a PEM file, which
neither a SimplySign cloud certificate nor the Apple keychain identity exposes), and the Linux binaries, which
have no signing convention.

## Troubleshooting

- **`AccessDeniedException: C:\WINDOWS\system32\config`**: on Windows, Claude Desktop may launch the server with `C:\WINDOWS\system32` as the working directory, causing Quarkus to fail when scanning for config files. The `-Duser.dir` argument in the example config overrides the working directory, and `-Dquarkus.config.locations=.` prevents Quarkus from scanning restricted system directories. Point `-Duser.dir` to any directory your user can write to.
- **Server disconnects immediately**: make sure `-Dquarkus.mcp.server.stdio.enabled=true` is in the `args` before `-jar`.
- **No log output visible**: Quarkus logs go to `mcp-email-server.log` (in the working directory), not to stdout/stderr, to avoid interfering with the STDIO transport. Check that file for errors. With the MCP Bundle the working directory is your home directory, so the log is `~/mcp-email-server.log`.
- **Authentication errors**: for Gmail, you need an [App Password](https://myaccount.google.com/apppasswords), not your regular password. Make sure 2-Step Verification is enabled on your Google account first.
- **Build fails** (building from source): ensure `JAVA_HOME` points to JDK 21+. The system `java` on PATH may differ from what Maven uses.
- **"Unknown account" errors**: call `listAccounts` first to see which accounts are configured. Account names are lowercase as defined in the environment variables (e.g., `work`, `gmail`).

## License

The server is released under the BSD 3-Clause License, see [LICENSE](LICENSE). The release artifacts bundle
third-party libraries under their own licenses; they are listed, with their licenses, in
[THIRD-PARTY.md](THIRD-PARTY.md). Note in particular that PDF text extraction uses iText 7, which is
licensed under the GNU Affero General Public License v3.0; see the note in that file.
