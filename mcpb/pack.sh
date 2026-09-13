#!/usr/bin/env bash
# Pack one MCP Bundle (.mcpb) around a native binary.
#
#   mcpb/pack.sh <version> <suffix> <platform> <binary> <output.mcpb>
#
#   version   release version without the "v" prefix, e.g. 1.0.12
#   suffix    artifact suffix: linux-x86_64 | linux-aarch64 | macos-aarch64 | windows-x86_64
#   platform  MCPB platform key: linux | darwin | win32
#   binary    path to the (already signed, where applicable) native binary
#   output    path of the bundle to write
#
# Used by the release workflow (one bundle per native image) and by the manual "Bundles" workflow that
# builds bundles for an already published release. Run it on the platform that owns the binary: the
# executable bit is taken from the file, and a Unix binary packed on Windows would lose it.
set -euo pipefail

if [ $# -ne 5 ]; then
	echo "usage: $0 <version> <suffix> <platform> <binary> <output.mcpb>" >&2
	exit 2
fi
VERSION="$1"; SUFFIX="$2"; PLATFORM="$3"; BINARY="$4"; OUTPUT="$5"
HERE="$(cd "$(dirname "$0")" && pwd)"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

NAME="$(basename "$BINARY")"
mkdir -p "$STAGE/server"
cp "$BINARY" "$STAGE/server/$NAME"
chmod +x "$STAGE/server/$NAME"
sed -e "s/__VERSION__/${VERSION}/g" -e "s/__BINARY__/${NAME}/g" -e "s/__PLATFORM__/${PLATFORM}/g" \
	"$HERE/manifest.json" > "$STAGE/manifest.json"

npx -y @anthropic-ai/mcpb validate "$STAGE/manifest.json"
npx -y @anthropic-ai/mcpb pack "$STAGE" "$OUTPUT"
echo "packed $OUTPUT ($SUFFIX)"
