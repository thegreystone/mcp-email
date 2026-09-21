# Developing the Email MCP Server

This page is for contributors and anyone running a development build. For installing and using the server,
see the [README](../README.md).

## Uber-jar

**Prerequisites:** Java 21+ and Maven 3.9+

```bash
mvn package
```

The uber-jar will be at `target/mcp-email-server-<version>-runner.jar`.

## Native image

**Prerequisites:** [GraalVM 25](https://www.graalvm.org/downloads/) with `native-image`, and Maven 3.9+. On Windows, Visual Studio 2022 with the "Desktop development with C++" workload is also required.

```bash
mvn package -Dnative -DskipTests
```

The native binary will be at `target/mcp-email-server-<version>-runner` (or `.exe` on Windows).

Two integration tests run against the built binary rather than the classes: a sanity test of the MCP
handshake and PDF extraction, and a mailbox scenario that drives the binary over STDIO against an in-process
IMAP and SMTP server (GreenMail), reading, moving, drafting and sending. They are skipped unless the binary is
named, and the release workflow runs them on every platform's binary:

```bash
mvn test-compile failsafe:integration-test failsafe:verify -Dnative.image.path=target/mcp-email-server-<version>-runner
```

## MCP Bundles

Pushing a `v<version>` tag runs the release workflow, which builds the uber-jar and the four native images and
packs an [MCP Bundle](https://github.com/anthropics/mcpb) (`.mcpb`) for macOS and Windows, the two platforms
that have Claude Desktop. A bundle is the binary under `server/` plus a `manifest.json` filled in from
[`mcpb/manifest.json`](../mcpb/manifest.json) (`__VERSION__`, `__BINARY__` and `__PLATFORM__` are substituted).
The manifest declares one account's IMAP and SMTP settings, the optional Drafts and spam folders and the two
safety switches as `user_config`, mapped to the `EMAIL_ACCOUNTS_DEFAULT_*`, `EMAIL_ALLOW_SENDING` and
`EMAIL_ALLOW_DELETION` environment variables, and passes the same `-Duser.dir` and
`-Dquarkus.config.locations` arguments as the [Claude Desktop example](../README.md#setting-up-with-claude-desktop) in the README, with the home directory as
working directory. The packing is [`mcpb/pack.sh`](../mcpb/pack.sh), run on the runner that built the binary so the executable bit survives on macOS.

The release also packs a **universal** bundle, `mcp-email-server-<version>-universal.mcpb`, with the macOS
and Windows binaries and a manifest whose `platform_overrides` pick one per operating system
([`mcpb/manifest-universal.json`](../mcpb/manifest-universal.json)). It is for the cases where one file has to
serve both platforms; direct downloads should keep using the per-platform bundles, which are smaller.
Linux is left out, since Claude Desktop does not run there and it keeps the bundle around 35 MB; Linux users
take the standalone binary. [`mcpb/pack-universal.sh`](../mcpb/pack-universal.sh) runs on Linux only, since a
pack done on Windows cannot set the executable bit of the macOS binary.

The manual **Bundles** workflow ([`.github/workflows/bundle.yml`](../.github/workflows/bundle.yml)) builds the
bundles for an already published release from its binaries and attaches them: run it from the Actions tab
with the version, choosing *all*, *macos-only* or *universal*.

To try a bundle locally (Git Bash on Windows):

```bash
bash mcpb/pack.sh 0.0.0 windows-x86_64 win32 target/mcp-email-server-*-runner.exe target/mcp-email-server-dev.mcpb
```

## Signing the macOS binary

The macOS job signs the binary with the Apple Developer ID certificate (hardened runtime, timestamped) and
submits it to Apple's notary service before packing the bundle, following the same recipe as
[thegreystone/rpg-mcp](https://github.com/thegreystone/rpg-mcp) and
[thegreystone/diskspace](https://github.com/thegreystone/diskspace). A bare executable cannot be stapled, so
Gatekeeper fetches the notarization ticket from Apple on first run; the [Quick Start](../README.md#quick-start) in the README keeps the `xattr` note for
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

## Signing the Windows binary

The Windows binary is Authenticode-signed with a Certum code-signing certificate through SimplySign Desktop.
SimplySign needs an interactive login (OTP from the mobile app), so signing is a manual step *after* the
workflow has published the release. With SimplySign Desktop running and logged in, and `gh` authenticated:

```powershell
.\mcpb\Sign-Release.ps1 -Version 1.0.12            # add -NoUpload to inspect target\signed first
```

[`mcpb/Sign-Release.ps1`](../mcpb/Sign-Release.ps1) downloads the Windows binary from the release, signs it
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

- **Build fails**: ensure `JAVA_HOME` points to JDK 21+. The system `java` on PATH may differ from what Maven uses.
