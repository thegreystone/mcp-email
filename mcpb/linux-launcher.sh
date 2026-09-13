#!/bin/sh
# Linux entry point of the universal MCP Bundle. The bundle manifest can only select a command per operating
# system, not per architecture, so this script checks that the x86_64 native image next to it can run here.
# Linux aarch64 is not in the bundle (size); those users take the standalone binary from the release page.
dir="$(cd "$(dirname "$0")" && pwd)"
case "$(uname -m)" in
	x86_64 | amd64) ;;
	*)
		echo "mcp-email: the universal bundle has no Linux build for $(uname -m); download the standalone binary from https://github.com/thegreystone/mcp-email/releases" >&2
		exit 1
		;;
esac
exec "$dir/mcp-email-server-__VERSION__-linux-x86_64" "$@"
