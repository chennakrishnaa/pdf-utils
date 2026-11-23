#!/usr/bin/env bash
# Release smoke test: boots the `pdfutils-smoke` AVD headless (same approach as emulator-smoke.sh),
# installs the signed release APK from dist/release/, launches its launcher activity, waits, and
# fails on a dead process or a crash in logcat. Always shuts the emulator down.
# Prints "RELEASE SMOKE OK" on success, "RELEASE SMOKE FAIL: <reason>" and exits non-zero otherwise.
#
# Run ./scripts/release.sh first. Environment overrides: ANDROID_HOME, JAVA_HOME, AVD_NAME
# (default pdfutils-smoke), BOOT_TIMEOUT seconds (default 300), EMULATOR_PORT (default 5584),
# RELEASE_APK (default: the newest dist/release/*.apk), SETTLE_SECONDS (default 10).
#
# MuPDF is loaded lazily, on the first PDF operation; a non-debuggable release build cannot be
# driven into that without instrumentation or UI scripting, so this test does not force the load.
# It does check that the device installed the x86_64 ABI and that the APK carries libmupdf_java.so
# for it, and reports whether the library happens to be mapped in the process.
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

AVD_NAME="${AVD_NAME:-pdfutils-smoke}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"
PORT="${EMULATOR_PORT:-5584}"
SETTLE_SECONDS="${SETTLE_SECONDS:-10}"
SERIAL="emulator-$PORT"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"
AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"
LOG="$(mktemp -t pdfutils-release-emulator.XXXXXX.log)"
EMU_PID=""
INSTALLED=""

fail() {
    echo "RELEASE SMOKE FAIL: $*" >&2
    exit 1
}

cleanup() {
    local status=$?
    if [[ -n "$EMU_PID" ]]; then
        # Do not leave the release-signed copy behind: emulator-smoke.sh's debug install would clash.
        if [[ -n "$INSTALLED" ]]; then
            if timeout 60 "$ADB" -s "$SERIAL" uninstall "$APP_ID" >/dev/null 2>&1; then
                echo "==> Uninstalled $APP_ID"
            else
                echo "==> Could not uninstall $APP_ID (may not be installed)" >&2
            fi
        fi
        "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
        for _ in $(seq 1 20); do
            kill -0 "$EMU_PID" 2>/dev/null || break
            sleep 1
        done
        if kill -0 "$EMU_PID" 2>/dev/null; then
            pkill -P "$EMU_PID" >/dev/null 2>&1 || true
            kill "$EMU_PID" >/dev/null 2>&1 || true
        fi
        # sg/emulator wrappers may leave the qemu child running under another parent.
        pkill -f -- "qemu-system.*-avd $AVD_NAME" >/dev/null 2>&1 || true
        echo "==> Emulator stopped (log: $LOG)"
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

for tool in "$EMULATOR" "$ADB" "$AAPT2"; do
    [[ -x "$tool" ]] || fail "required tool not found: $tool"
done
command -v unzip >/dev/null || fail "unzip is required"

# The APK under test.
if [[ -z "${RELEASE_APK:-}" ]]; then
    RELEASE_APK="$(ls -t "$ROOT"/dist/release/*.apk 2>/dev/null | head -n1 || true)"
fi
[[ -n "$RELEASE_APK" && -f "$RELEASE_APK" ]] || fail "no release APK in dist/release/ (run ./scripts/release.sh first)"
APP_ID="$("$AAPT2" dump packagename "$RELEASE_APK")" || fail "aapt2 dump packagename failed"
LAUNCHER="$("$AAPT2" dump badging "$RELEASE_APK" | sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" | head -n1)"
[[ -n "$APP_ID" && -n "$LAUNCHER" ]] || fail "could not read applicationId/launcher activity from $RELEASE_APK"
entries="$(unzip -Z1 "$RELEASE_APK")" || fail "cannot list $RELEASE_APK"
grep -qxF 'lib/x86_64/libmupdf_java.so' <<<"$entries" \
    || fail "$RELEASE_APK lacks lib/x86_64/libmupdf_java.so (needed on the x86_64 emulator)"
echo "==> APK: ${RELEASE_APK#"$ROOT"/} ($APP_ID, launcher $LAUNCHER)"

if ! "$EMULATOR" -list-avds 2>/dev/null | grep -qxF "$AVD_NAME"; then
    fail "AVD '$AVD_NAME' not found (see scripts/emulator-smoke.sh for how to create it)"
fi
if "$ADB" devices | grep -q "^$SERIAL[[:space:]]"; then
    fail "an emulator is already running on port $PORT ($SERIAL); stop it or set EMULATOR_PORT"
fi
if pgrep -f -- "qemu-system.*-avd $AVD_NAME" >/dev/null 2>&1; then
    fail "AVD '$AVD_NAME' is already running (another smoke run?); not touching it"
fi

EMU_CMD=("$EMULATOR" -avd "$AVD_NAME" -port "$PORT" -no-window -no-audio -no-boot-anim
    -no-snapshot -gpu swiftshader_indirect -memory 2048)

echo "==> Starting $AVD_NAME on $SERIAL (log: $LOG)"
"$ADB" start-server >/dev/null 2>&1 || true
START=$(date +%s)
if [[ -r /dev/kvm && -w /dev/kvm ]]; then
    "${EMU_CMD[@]}" >"$LOG" 2>&1 </dev/null &
elif command -v sg >/dev/null && getent group kvm | grep -qw "$(id -un)"; then
    # Member of kvm, but the group is not active in this login yet.
    sg kvm -c "$(printf '%q ' "${EMU_CMD[@]}")" >"$LOG" 2>&1 </dev/null &
else
    fail "no access to /dev/kvm (run: sudo gpasswd -a $(id -un) kvm)"
fi
EMU_PID=$!

echo "==> Waiting for boot (timeout ${BOOT_TIMEOUT}s)"
booted=""
while (( $(date +%s) - START < BOOT_TIMEOUT )); do
    if ! kill -0 "$EMU_PID" 2>/dev/null; then
        tail -n 20 "$LOG" >&2 || true
        fail "emulator exited during boot (see $LOG)"
    fi
    booted="$(timeout 5 "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    [[ "$booted" == "1" ]] && break
    sleep 3
done
if [[ "$booted" != "1" ]]; then
    tail -n 20 "$LOG" >&2 || true
    fail "emulator did not finish booting within ${BOOT_TIMEOUT}s"
fi
echo "==> Booted in $(( $(date +%s) - START ))s"

adb_s() { "$ADB" -s "$SERIAL" "$@"; }

echo "==> Uninstalling any installed $APP_ID"
adb_s uninstall "$APP_ID" >/dev/null 2>&1 || true
echo "==> Installing ${RELEASE_APK##*/}"
INSTALLED=1
adb_s install "$RELEASE_APK" || fail "adb install failed"

abi="$(adb_s shell dumpsys package "$APP_ID" | tr -d '\r' | sed -n 's/^ *primaryCpuAbi=//p' | head -n1)"
echo "installed primaryCpuAbi: ${abi:-<unknown>}"
[[ "$abi" == "x86_64" ]] || fail "expected primaryCpuAbi=x86_64, got '${abi:-<unknown>}'"

adb_s logcat -c || true
adb_s logcat -b crash -c >/dev/null 2>&1 || true

echo "==> am start -W -n $APP_ID/$LAUNCHER"
start_out="$(adb_s shell am start -W -n "$APP_ID/$LAUNCHER" | tr -d '\r')" || fail "am start failed"
echo "$start_out"
grep -q '^Status: ok' <<<"$start_out" || fail "am start did not report Status: ok"

echo "==> Waiting ${SETTLE_SECONDS}s"
sleep "$SETTLE_SECONDS"

pid="$(adb_s shell pidof "$APP_ID" | tr -d '\r' || true)"
crashes="$( { adb_s logcat -d; adb_s logcat -b crash -d; } 2>/dev/null | tr -d '\r' \
    | grep -E "FATAL EXCEPTION|Fatal signal|>>> $APP_ID <<<|Process: $APP_ID|AndroidRuntime.*$APP_ID" || true)"
if [[ -n "$crashes" ]]; then
    echo "$crashes" | head -n 40 >&2
    fail "crash in logcat for $APP_ID"
fi
[[ -n "$pid" ]] || fail "$APP_ID is not running ${SETTLE_SECONDS}s after launch"
echo "process alive: pid $pid"
echo "logcat: no FATAL EXCEPTION / native crash for $APP_ID"

focus="$(adb_s shell dumpsys window | tr -d '\r' | grep -m1 -E 'mCurrentFocus|mFocusedApp' || true)"
echo "focus: ${focus## }"
grep -q "$APP_ID" <<<"$focus" || fail "$APP_ID is not in the foreground"

# Informational: whether MuPDF is already mapped (needs root to read another app's maps).
if adb_s root >/dev/null 2>&1 && adb_s wait-for-device && adb_s shell id 2>/dev/null | grep -q 'uid=0'; then
    if adb_s shell cat "/proc/$pid/maps" 2>/dev/null | grep -q libmupdf_java.so; then
        echo "libmupdf_java.so: mapped in $APP_ID"
    else
        echo "libmupdf_java.so: not mapped yet (loaded lazily on the first PDF operation; not forced here)"
    fi
else
    echo "libmupdf_java.so: not checked (no root on this image)"
fi

echo "RELEASE SMOKE OK"
