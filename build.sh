#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
if [[ -n "${PLUGIN_JDK:-}" ]]; then
  plugin_jdk="$PLUGIN_JDK"
elif [[ -x "/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home/bin/javac" ]]; then
  plugin_jdk="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
else
  plugin_jdk="$(/usr/libexec/java_home -v 21)"
fi
plugin_sdk="${PLUGIN_SDK:-$HOME/Library/Caches/coding-transfer-sdk-2025.1.2}"
"$plugin_jdk/bin/java" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 BuildPlugin.java "${1:-build}" "$plugin_sdk"
