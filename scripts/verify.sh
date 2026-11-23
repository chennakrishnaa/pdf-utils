#!/usr/bin/env bash
# Builds, tests and lints the app, then checks the debug and release APKs for:
#   - no declared or requested permissions,
#   - no exported component other than MainActivity,
#   - native libs only for arm64-v8a / x86_64, with MuPDF present for arm64-v8a.
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"
APKANALYZER="$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"
ALLOWED_EXPORTED="app.venkat.filetools.MainActivity"

fail() {
    echo "VERIFY FAIL: $*" >&2
    exit 1
}

for tool in "$AAPT2" "$APKANALYZER"; do
    [[ -x "$tool" ]] || fail "required tool not found: $tool"
done
command -v python3 >/dev/null || fail "python3 is required to parse the manifest"
command -v unzip >/dev/null || fail "unzip is required to list APK entries"

echo "==> ./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug"
./gradlew --console=plain assembleDebug assembleRelease testDebugUnitTest lintDebug \
    || fail "gradle build/test/lint failed"

# Reads a manifest XML on stdin; prints one fully-qualified name per exported component.
list_exported() {
    python3 -I -c '
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
root = ET.fromstring(sys.stdin.read())
pkg = root.get("package", "")
app = root.find("application")
if app is None:
    sys.exit(0)
for el in app:
    if el.tag not in ("activity", "activity-alias", "service", "receiver", "provider"):
        continue
    exported = el.get(A + "exported")
    if exported is None:
        # Pre-API-31 implicit export: a component with an intent filter is exported.
        exported = "true" if el.find("intent-filter") is not None else "false"
    if exported.lower() == "true":
        name = el.get(A + "name", "")
        if name.startswith("."):
            name = pkg + name
        elif "." not in name:
            name = pkg + "." + name
        print(name)
'
}

check_apk() {
    local label="$1" apk="$2"
    echo "==> Checking $label APK: $apk"
    [[ -f "$apk" ]] || fail "$label APK not found at $apk"

    # 1. Permissions.
    local perms
    perms="$("$AAPT2" dump permissions "$apk")" || fail "$label: aapt2 dump permissions failed"
    echo "--- aapt2 dump permissions ($label):"
    echo "$perms"
    if grep -qE 'uses-permission|permission:' <<<"$perms"; then
        fail "$label APK declares or requests permissions: $(grep -E 'uses-permission|permission:' <<<"$perms" | tr '\n' ' ')"
    fi

    # 2. Exported components.
    local manifest exported
    manifest="$("$APKANALYZER" manifest print "$apk")" || fail "$label: apkanalyzer manifest print failed"
    exported="$(list_exported <<<"$manifest")" || fail "$label: could not parse manifest"
    echo "--- exported components ($label):"
    echo "${exported:-<none>}"
    local comp
    while IFS= read -r comp; do
        [[ -z "$comp" ]] && continue
        [[ "$comp" == "$ALLOWED_EXPORTED" ]] || fail "$label APK has unexpected exported component: $comp"
    done <<<"$exported"
    grep -qxF "$ALLOWED_EXPORTED" <<<"$exported" || fail "$label APK: $ALLOWED_EXPORTED is not exported"

    # 3. Native ABIs.
    local entries
    entries="$(unzip -Z1 "$apk")" || fail "$label: cannot list APK entries"
    if grep -qE '^lib/(armeabi-v7a|x86)/' <<<"$entries"; then
        fail "$label APK contains disallowed ABI entries: $(grep -E '^lib/(armeabi-v7a|x86)/' <<<"$entries" | tr '\n' ' ')"
    fi
    grep -qxF 'lib/arm64-v8a/libmupdf_java.so' <<<"$entries" \
        || fail "$label APK lacks lib/arm64-v8a/libmupdf_java.so"
    echo "--- native libs ($label):"
    grep '^lib/' <<<"$entries" || true
}

check_apk debug "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
# The release APK is app-release-unsigned.apk without a signing key and app-release.apk with one
# (see RELEASING.md); take the name of the one just built from AGP's output metadata.
RELEASE_DIR="$ROOT/app/build/outputs/apk/release"
RELEASE_APK_NAME="$(python3 -I -c '
import json, sys
print(json.load(open(sys.argv[1]))["elements"][0]["outputFile"])
' "$RELEASE_DIR/output-metadata.json")" || fail "cannot read $RELEASE_DIR/output-metadata.json"
check_apk release "$RELEASE_DIR/$RELEASE_APK_NAME"

echo "VERIFY OK"
