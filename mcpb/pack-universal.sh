#!/usr/bin/env bash
# Pack the universal MCP Bundle: the Linux x86_64, macOS and Windows native binaries in one .mcpb, with the
# manifest's platform_overrides choosing the right one at launch. This is the bundle a Claude plugin marketplace
# entry points at, since a plugin can reference only one bundle for all platforms. Linux aarch64 is left out
# to keep the bundle around 50 MB; those users take the standalone binary.
#
#   mcpb/pack-universal.sh <version> <binaries-dir> <output.mcpb>
#
#   version       release version without the "v" prefix, e.g. 1.0.12
#   binaries-dir  directory holding mcp-email-server-<version>-{linux-x86_64,macos-aarch64,windows-x86_64.exe}
#   output        path of the bundle to write
#
# Run on Linux or macOS: the executable bits of the Unix binaries and the launcher must end up in the zip, and
# a pack done on Windows cannot set them.
set -euo pipefail

if [ $# -ne 3 ]; then
	echo "usage: $0 <version> <binaries-dir> <output.mcpb>" >&2
	exit 2
fi
VERSION="$1"; SRC="$2"; OUTPUT="$3"
HERE="$(cd "$(dirname "$0")" && pwd)"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/server"
for suffix in linux-x86_64 macos-aarch64 windows-x86_64.exe; do
	name="mcp-email-server-${VERSION}-${suffix}"
	if [ ! -f "$SRC/$name" ]; then
		echo "missing binary: $SRC/$name" >&2
		exit 1
	fi
	cp "$SRC/$name" "$STAGE/server/$name"
	chmod +x "$STAGE/server/$name"
done
sed -e "s/__VERSION__/${VERSION}/g" "$HERE/linux-launcher.sh" > "$STAGE/server/mcp-email-server-${VERSION}-linux"
chmod +x "$STAGE/server/mcp-email-server-${VERSION}-linux"
sed -e "s/__VERSION__/${VERSION}/g" "$HERE/manifest-universal.json" > "$STAGE/manifest.json"

npx -y @anthropic-ai/mcpb validate "$STAGE/manifest.json"
npx -y @anthropic-ai/mcpb pack "$STAGE" "$OUTPUT"
echo "packed $OUTPUT (universal)"
