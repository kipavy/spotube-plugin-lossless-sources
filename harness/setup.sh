#!/usr/bin/env bash
# Prepares the dependencies the runtime harness needs.
#
# The harness runs the compiled plugin on plain Dart, with no Flutter and no
# Spotube. Two upstream packages provide the runtime it will meet in the app:
#
#   hetu_std             -- module:std (HttpClient, Base64, JSON, FutureUtils)
#   hetu_spotube_plugin  -- module:spotube_plugin, and its prebuilt bytecode
#
# hetu_std declares a Flutter dependency that it only uses to read its own
# bytecode out of an asset bundle. The harness loads that bytecode from disk
# instead (loadBytecodePureDart), so the import is stripped here and the whole
# thing runs under the plain Dart SDK.
set -euo pipefail

cd "$(dirname "$0")"

STD_DIR=".deps/hetu_std"
SPOTUBE_DIR=".deps/hetu_spotube_plugin"

mkdir -p .deps

if [ ! -d "$STD_DIR" ]; then
  git clone --depth 1 https://github.com/hetu-community/hetu_std.git "$STD_DIR"
fi

if [ ! -d "$SPOTUBE_DIR" ]; then
  git clone --depth 1 https://github.com/KRTirtho/hetu_spotube_plugin.git "$SPOTUBE_DIR"
fi

python3 - "$STD_DIR" <<'PY'
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])

pubspec = root / "pubspec.yaml"
text = pubspec.read_text()
text = text.replace("  flutter:\n    sdk: flutter\n", "")
text = re.sub(r'^\s*flutter: ">=3\.0\.0 <4\.0\.0"\n', "", text, flags=re.M)
pubspec.write_text(text)

library = root / "lib" / "hetu_std.dart"
source = library.read_text()
source = source.replace("import 'package:flutter/services.dart';\n", "")
source = re.sub(
    r"  /// Loads the bytecode for the standard library from the Flutter asset bundle\..*?\n  \}\n",
    "",
    source,
    flags=re.S,
)
library.write_text(source)
PY

echo "harness dependencies ready"
