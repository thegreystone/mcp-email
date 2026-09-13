<#
.SYNOPSIS
    Authenticode-sign the Windows binary of a published release and re-pack its MCP Bundle.

.DESCRIPTION
    The release workflow signs and notarizes the macOS binary in CI, but publishes the Windows binary unsigned:
    Windows code signing uses a Certum certificate through SimplySign Desktop, which needs an interactive login
    (OTP from the mobile app) and has no clean headless path on GitHub-hosted runners. This script closes the gap
    after the fact, the same way thegreystone/diskspace does it:

      1. downloads mcp-email-server-<version>-windows-x86_64.exe from the GitHub release,
      2. signs it (SHA-256, RFC 3161 timestamp from Certum) and verifies signature + timestamp,
      3. re-creates mcp-email-server-<version>-windows-x86_64.mcpb around the signed binary,
      4. uploads both assets back to the release, replacing the unsigned ones,
      5. dispatches the Bundles workflow to re-pack the universal bundle (Linux-only job, for executable bits).

    Preconditions: SimplySign Desktop logged in (cert visible in Cert:\CurrentUser\My); signtool (Windows SDK);
    gh authenticated against the repo; node/npx for @anthropic-ai/mcpb.

.PARAMETER Version
    Release version without the "v" prefix, e.g. 1.0.12.

.PARAMETER Thumbprint
    SHA-1 thumbprint of the code-signing certificate. Defaults to $env:CERTUM_SIGN_THUMBPRINT, then
    $env:DISKSPACE_SIGN_THUMBPRINT (same certificate). Set one of them in your $PROFILE: the thumbprint is not
    secret, but keeping it out of the repo makes certificate renewal a one-line edit instead of a commit.
    Always pin by thumbprint; never use "signtool /a", which can silently pick the wrong certificate.

.PARAMETER NoUpload
    Sign and pack under target\signed but do not touch the GitHub release.

.EXAMPLE
    .\mcpb\Sign-Release.ps1 -Version 1.0.12
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $Version,
    [string] $Thumbprint,
    [string] $Repo = 'thegreystone/mcp-email',
    [string] $TimestampUrl = 'http://time.certum.pl',
    [string] $SignTool,
    [switch] $NoUpload
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

if (-not $Thumbprint) { $Thumbprint = $env:CERTUM_SIGN_THUMBPRINT }
if (-not $Thumbprint) { $Thumbprint = $env:DISKSPACE_SIGN_THUMBPRINT }
if (-not $Thumbprint) {
    throw 'No thumbprint. Set $env:CERTUM_SIGN_THUMBPRINT in your $PROFILE or pass -Thumbprint.'
}

if (-not $SignTool) {
    $SignTool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin" -Recurse -Filter signtool.exe -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\x64\\' } |
        Sort-Object FullName -Descending |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $SignTool) {
        throw 'signtool.exe not found under Windows Kits 10. Install the Windows SDK or pass -SignTool.'
    }
}

$cert = Get-ChildItem Cert:\CurrentUser\My | Where-Object { $_.Thumbprint -eq $Thumbprint }
if (-not $cert) {
    throw "Cert $Thumbprint not found in Cert:\CurrentUser\My. Open SimplySign Desktop and log in (OTP from the mobile app), then retry."
}
Write-Host "Signing as: $($cert.Subject) (expires $($cert.NotAfter.ToShortDateString()))"

$binary = "mcp-email-server-$Version-windows-x86_64.exe"
$bundle = "mcp-email-server-$Version-windows-x86_64.mcpb"
$work = Join-Path (Get-Location) 'target\signed'
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force (Join-Path $work 'mcpb\server') | Out-Null

function Invoke-SignAndVerify([string] $path, [string] $label) {
    Write-Host "Signing $label..."
    & $SignTool sign /tr $TimestampUrl /td sha256 /fd sha256 /sha1 $Thumbprint /d 'Email MCP Server' /du "https://github.com/$Repo" $path
    if ($LASTEXITCODE -ne 0) { throw "signtool failed for $label (exit $LASTEXITCODE)" }
    $sig = Get-AuthenticodeSignature $path
    if ($sig.Status -ne 'Valid') { throw "Signature on $label is $($sig.Status): $($sig.StatusMessage)" }
    if (-not $sig.TimeStamperCertificate) {
        throw "Signature on $label has no timestamp; without it the signature expires with the certificate."
    }
    Write-Host "  Subject:   $($sig.SignerCertificate.Subject)"
    Write-Host "  Timestamp: $($sig.TimeStamperCertificate.Subject)"
}

# 1. Download the unsigned binary.
Write-Host "Downloading $binary from release v$Version..."
$release = gh release view "v$Version" --repo $Repo --json tagName 2>$null
if ($LASTEXITCODE -ne 0 -or -not $release) { throw "release v$Version not found on $Repo" }
gh release download "v$Version" --repo $Repo --pattern $binary --dir $work
if ($LASTEXITCODE -ne 0) { throw "gh release download failed for $binary (exit $LASTEXITCODE)" }
$exe = Join-Path $work $binary

# 2. Sign and verify.
Invoke-SignAndVerify $exe $binary

# 3. Re-pack the bundle around the signed binary. The manifest carries no file hashes, so this is a valid bundle.
Write-Host "Re-packing $bundle..."
Copy-Item $exe (Join-Path $work "mcpb\server\$binary")
$manifest = Get-Content 'mcpb\manifest.json' -Raw -Encoding UTF8
$manifest = $manifest.Replace('__VERSION__', $Version).Replace('__BINARY__', $binary).Replace('__PLATFORM__', 'win32')
[System.IO.File]::WriteAllText((Join-Path $work 'mcpb\manifest.json'), $manifest, (New-Object System.Text.UTF8Encoding $false))
npx -y @anthropic-ai/mcpb validate (Join-Path $work 'mcpb\manifest.json')
if ($LASTEXITCODE -ne 0) { throw 'manifest validation failed' }
npx -y @anthropic-ai/mcpb pack (Join-Path $work 'mcpb') (Join-Path $work $bundle)
if ($LASTEXITCODE -ne 0) { throw 'mcpb pack failed' }

if ($NoUpload) {
    Write-Host "Signed artifacts left in $work (upload skipped)."
    return
}

# 4. Replace the release assets.
Write-Host "Replacing release assets..."
gh release upload "v$Version" --repo $Repo --clobber $exe (Join-Path $work $bundle)
if ($LASTEXITCODE -ne 0) { throw "gh release upload failed (exit $LASTEXITCODE)" }
Write-Host "Done. Release v$Version now has a signed $binary and a $bundle wrapping it." -ForegroundColor Green

# 5. The universal bundle also contains the Windows binary, but it must be packed on Linux (executable bits),
#    so hand that to the Bundles workflow.
Write-Host "Dispatching the Bundles workflow to re-pack the universal bundle around the signed binary..."
gh workflow run bundle.yml --repo $Repo -f "version=$Version" -f platforms=universal
if ($LASTEXITCODE -ne 0) { throw 'gh workflow run failed; run the Bundles workflow (universal) by hand from the Actions tab' }
