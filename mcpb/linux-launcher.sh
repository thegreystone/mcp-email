#!/bin/sh
# Linux entry point of the universal MCP Bundle. The bundle manifest can only select a command per operating
# system, not per architecture, so this script picks the x86_64 or aarch64 native image next to it.
dir="$(cd "$(dirname "$0")" && pwd)"
case "$(uname -m)" in
	x86_64 | amd64) arch=x86_64 ;;
	aarch64 | arm64) arch=aarch64 ;;
	*) echo "mcp-email: no Linux build for architecture $(uname -m)" >&2; exit 1 ;;
esac
exec "$dir/mcp-email-server-__VERSION__-linux-$arch" "$@"
