#!/usr/bin/env bash
# Builds the signed release APK and Play app bundle, re-checks the signed APK, and copies both to
# dist/release/<applicationId>-<versionName>.{apk,aab}. Prints "RELEASE OK" on success,
# "RELEASE FAIL: <reason>" and exits non-zero otherwise.
#
# Signing info comes from <repo>/keystore.properties or the PDFUTILS_* environment variables
# (see RELEASING.md). Passwords are never printed.
#
# Usage: ./scripts/release.sh [--skip-verify]
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BUILD_TOOLS="$ANDROID_HOME/build-tools/36.0.0"
AAPT2="$BUILD_TOOLS/aapt2"
APKSIGNER="$BUILD_TOOLS/apksigner"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKANALYZER="$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"
JARSIGNER="$JAVA_HOME/bin/jarsigner"
OUT_DIR="$ROOT/dist/release"

fail() {
    echo "RELEASE FAIL: $*" >&2
    exit 1
}

SKIP_VERIFY=""
for arg in "$@"; do
    case "$arg" in
        --skip-verify) SKIP_VERIFY=1 ;;
        -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
        *) fail "unknown argument: $arg (usage: $0 [--skip-verify])" ;;
    esac
done

for tool in "$AAPT2" "$APKSIGNER" "$ZIPALIGN" "$APKANALYZER" "$JARSIGNER"; do
    [[ -x "$tool" ]] || fail "required tool not found: $tool"
done
command -v python3 >/dev/null || fail "python3 is required"
command -v unzip >/dev/null || fail "unzip is required"

# 1. Signing info must be present (the Gradle build itself rejects an incomplete set).
if [[ -f "$ROOT/keystore.properties" ]]; then
    echo "==> Signing info: keystore.properties"
elif [[ -n "${PDFUTILS_STORE_FILE:-}" || -n "${PDFUTILS_STORE_PASSWORD:-}" \
        || -n "${PDFUTILS_KEY_ALIAS:-}" || -n "${PDFUTILS_KEY_PASSWORD:-}" ]]; then
    for v in PDFUTILS_STORE_FILE PDFUTILS_STORE_PASSWORD PDFUTILS_KEY_ALIAS PDFUTILS_KEY_PASSWORD; do
        [[ -n "${!v:-}" ]] || fail "signing env var $v is not set (all four PDFUTILS_* are required)"
    done
    [[ -f "$PDFUTILS_STORE_FILE" ]] || fail "PDFUTILS_STORE_FILE does not point to a file"
    echo "==> Signing info: PDFUTILS_* environment variables"
else
    fail "no signing info: create keystore.properties at the repo root or set PDFUTILS_STORE_FILE, \
PDFUTILS_STORE_PASSWORD, PDFUTILS_KEY_ALIAS, PDFUTILS_KEY_PASSWORD (see RELEASING.md)"
fi

# 2. Full verification (build, unit tests, lint, APK checks).
if [[ -z "$SKIP_VERIFY" ]]; then
    echo "==> ./scripts/verify.sh"
    ./scripts/verify.sh || fail "verify.sh failed"
else
    echo "==> Skipping verify.sh (--skip-verify)"
fi

# 3. Build.
echo "==> ./gradlew assembleRelease bundleRelease"
./gradlew --console=plain assembleRelease bundleRelease || fail "gradle release build failed"

APK_DIR="$ROOT/app/build/outputs/apk/release"
APK_NAME="$(python3 -I -c '
import json, sys
print(json.load(open(sys.argv[1]))["elements"][0]["outputFile"])
' "$APK_DIR/output-metadata.json")" || fail "cannot read $APK_DIR/output-metadata.json"
APK="$APK_DIR/$APK_NAME"
AAB="$ROOT/app/build/outputs/bundle/release/app-release.aab"
[[ -f "$APK" ]] || fail "release APK not found: $APK"
[[ "$APK_NAME" != *unsigned* ]] || fail "Gradle produced an unsigned APK ($APK_NAME); signing config not applied"
[[ -f "$AAB" ]] || fail "release bundle not found: $AAB"

# 4. Signatures.
echo "==> apksigner verify $APK_NAME"
sig="$("$APKSIGNER" verify --verbose --print-certs "$APK" 2>&1)" || { echo "$sig" >&2; fail "apksigner verify failed"; }
grep -E '^Verifies$|^Verified using v[0-9.]+ scheme|^Signer #1 certificate (DN|SHA-256)' <<<"$sig" || true
grep -qx 'Verified using v2 scheme (APK Signature Scheme v2): true' <<<"$sig" || fail "APK is not v2-signed"
grep -qx 'Verified using v3 scheme (APK Signature Scheme v3): true' <<<"$sig" || fail "APK is not v3-signed"
CERT_SHA256="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$sig")"
[[ -n "$CERT_SHA256" ]] || fail "could not read signer certificate SHA-256"

echo "==> jarsigner -verify app-release.aab"
jsig="$("$JARSIGNER" -verify "$AAB" 2>&1)" || { echo "$jsig" >&2; fail "jarsigner -verify failed"; }
grep -q '^jar verified' <<<"$jsig" || { echo "$jsig" >&2; fail "bundle is not signed"; }
echo "jar verified"

# 5. Re-check the signed APK.
APP_ID="$("$AAPT2" dump packagename "$APK")" || fail "aapt2 dump packagename failed"
badging="$("$AAPT2" dump badging "$APK")" || fail "aapt2 dump badging failed"
VERSION_NAME="$(sed -n "s/^package: .* versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
LAUNCHER="$(sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -n1)"
[[ -n "$APP_ID" && -n "$VERSION_NAME" && -n "$LAUNCHER" ]] \
    || fail "could not read applicationId/versionName/launcher activity from the APK"
echo "==> applicationId=$APP_ID versionName=$VERSION_NAME launcher=$LAUNCHER"

perms="$("$AAPT2" dump permissions "$APK")" || fail "aapt2 dump permissions failed"
if grep -qE 'uses-permission|permission:' <<<"$perms"; then
    fail "signed APK declares or requests permissions: $(grep -E 'uses-permission|permission:' <<<"$perms" | tr '\n' ' ')"
fi
echo "check permissions: OK (0 uses-permission)"

manifest="$("$APKANALYZER" manifest print "$APK")" || fail "apkanalyzer manifest print failed"
mcheck="$(python3 -I -c '
import sys
import xml.etree.ElementTree as ET
A = "{http://schemas.android.com/apk/res/android}"
root = ET.fromstring(sys.stdin.read())
pkg = root.get("package", "")
app = root.find("application")
debuggable = (app.get(A + "debuggable") if app is not None else None) or "absent"
print("debuggable=" + debuggable)
for el in (app if app is not None else []):
    if el.tag not in ("activity", "activity-alias", "service", "receiver", "provider"):
        continue
    exported = el.get(A + "exported")
    if exported is None:
        exported = "true" if el.find("intent-filter") is not None else "false"
    if exported.lower() == "true":
        name = el.get(A + "name", "")
        if name.startswith("."):
            name = pkg + name
        elif "." not in name:
            name = pkg + "." + name
        print("exported=" + name)
' <<<"$manifest")" || fail "could not parse manifest"
debuggable="$(sed -n 's/^debuggable=//p' <<<"$mcheck")"
exported="$(sed -n 's/^exported=//p' <<<"$mcheck")"
[[ "$debuggable" == "absent" || "$debuggable" == "false" ]] || fail "android:debuggable=$debuggable"
echo "check debuggable: OK ($debuggable)"
[[ "$exported" == "$LAUNCHER" ]] || fail "exported components must be exactly $LAUNCHER, got: $(tr '\n' ' ' <<<"$exported")"
echo "check exported: OK (only $LAUNCHER)"

entries="$(unzip -Z1 "$APK")" || fail "cannot list APK entries"
if grep -qE '^lib/(armeabi|armeabi-v7a|x86|mips|mips64)/' <<<"$entries"; then
    fail "signed APK contains 32-bit/legacy ABI libs: $(grep -E '^lib/(armeabi|armeabi-v7a|x86|mips|mips64)/' <<<"$entries" | tr '\n' ' ')"
fi
abis="$(grep -oE '^lib/[^/]+/' <<<"$entries" | sort -u | cut -d/ -f2 | tr '\n' ' ')"
grep -qxF 'lib/arm64-v8a/libmupdf_java.so' <<<"$entries" || fail "signed APK lacks lib/arm64-v8a/libmupdf_java.so"
echo "check ABIs: OK (${abis% })"

# 16 KB page alignment of uncompressed native libs (zipalign -P needs build-tools >= 35).
za_help="$("$ZIPALIGN" 2>&1 || true)"   # prints usage and exits non-zero
if grep -q -- '-P <pagesize_kb>' <<<"$za_help"; then
    za="$("$ZIPALIGN" -c -P 16 -v 4 "$APK" 2>&1)" || { grep -v '(OK' <<<"$za" >&2 || true; fail "zipalign -c -P 16 failed"; }
    so_total="$(grep -c '^lib/.*\.so$' <<<"$entries" || true)"
    so_ok="$(grep -cE 'lib/.*\.so \(OK' <<<"$za" || true)"
    (( so_total > 0 && so_ok == so_total )) \
        || fail "zipalign listed $so_ok/$so_total native libs as OK"
    echo "check 16 KB alignment: OK ($so_ok/$so_total .so entries; zipalign -c -P 16 -v 4)"
else
    echo "check 16 KB alignment: SKIPPED (zipalign in $BUILD_TOOLS has no -P option)"
fi

# 6. Copy outputs.
mkdir -p "$OUT_DIR"
OUT_APK="$OUT_DIR/$APP_ID-$VERSION_NAME.apk"
OUT_AAB="$OUT_DIR/$APP_ID-$VERSION_NAME.aab"
cp -f "$APK" "$OUT_APK"
cp -f "$AAB" "$OUT_AAB"

echo "==> Outputs"
echo "signer certificate SHA-256: $CERT_SHA256"
for f in "$OUT_APK" "$OUT_AAB"; do
    printf '%s  %s bytes  sha256=%s\n' "${f#"$ROOT"/}" "$(stat -c %s "$f")" "$(sha256sum "$f" | cut -d' ' -f1)"
done

echo "RELEASE OK"
