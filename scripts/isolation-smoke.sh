#!/usr/bin/env bash
# Process-isolation smoke test, run OUTSIDE instrumentation (the test APK's Fixtures load MuPDF
# into the app's main process, so this cannot be checked from an instrumented test).
#
# Boots the `pdfutils-smoke` AVD headless (as emulator-smoke.sh does), installs ONLY the debug APK
# (no test APK), launches MainActivity, starts the `:engine` process best-effort
# (`run-as <app> am start-service`, which creates the process but runs no PDF operation), and reads
# /proc/<pid>/maps through `run-as` (the debug APK is debuggable):
#   - asserts the maps read works (libc.so present) and the UI process maps NO libmupdf;
#   - reports whether the :engine process is alive and whether it maps libmupdf (MuPDF is loaded
#     lazily on the first operation, which this script does not drive, so that is informational);
#   - reports oom_score_adj of both processes.
# Always shuts the emulator down. Prints "ISOLATION SMOKE OK" or "ISOLATION SMOKE FAIL: <reason>".
#
# Environment overrides: ANDROID_HOME, JAVA_HOME, AVD_NAME (default pdfutils-smoke),
# BOOT_TIMEOUT seconds (default 300), EMULATOR_PORT (default 5584), SETTLE_SECONDS (default 8).
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

AVD_NAME="${AVD_NAME:-pdfutils-smoke}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"
PORT="${EMULATOR_PORT:-5584}"
SETTLE_SECONDS="${SETTLE_SECONDS:-8}"
SERIAL="emulator-$PORT"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"
AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
LOG="$(mktemp -t pdfutils-isolation-emulator.XXXXXX.log)"
EMU_PID=""

fail() {
    echo "ISOLATION SMOKE FAIL: $*" >&2
    exit 1
}

cleanup() {
    local status=$?
    if [[ -n "$EMU_PID" ]]; then
        "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
        for _ in $(seq 1 20); do
            kill -0 "$EMU_PID" 2>/dev/null || break
            sleep 1
        done
        if kill -0 "$EMU_PID" 2>/dev/null; then
            pkill -P "$EMU_PID" >/dev/null 2>&1 || true
            kill "$EMU_PID" >/dev/null 2>&1 || true
        fi
        pkill -f -- "qemu-system.*-avd $AVD_NAME" >/dev/null 2>&1 || true
        echo "==> Emulator stopped (log: $LOG)"
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

adb_s() { timeout 60 "$ADB" -s "$SERIAL" "$@"; }

for tool in "$EMULATOR" "$ADB" "$AAPT2"; do
    [[ -x "$tool" ]] || fail "required tool not found: $tool"
done
if "$ADB" devices | grep -q "^$SERIAL[[:space:]]"; then
    fail "an emulator is already running on port $PORT ($SERIAL); stop it or set EMULATOR_PORT"
fi

echo "==> ./gradlew assembleDebug"
./gradlew --console=plain -q assembleDebug || fail "building the debug APK failed"
APP_ID="$("$AAPT2" dump packagename "$APK")" || fail "aapt2 dump packagename failed"
LAUNCHER="$("$AAPT2" dump badging "$APK" | sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" | head -n1)"
[[ -n "$APP_ID" && -n "$LAUNCHER" ]] || fail "could not read applicationId/launcher activity from $APK"

EMU_CMD=("$EMULATOR" -avd "$AVD_NAME" -port "$PORT" -no-window -no-audio -no-boot-anim
    -no-snapshot -gpu swiftshader_indirect -memory 2048)
echo "==> Starting $AVD_NAME on $SERIAL (log: $LOG)"
"$ADB" start-server >/dev/null 2>&1 || true
START=$(date +%s)
if [[ -r /dev/kvm && -w /dev/kvm ]]; then
    "${EMU_CMD[@]}" >"$LOG" 2>&1 </dev/null &
elif command -v sg >/dev/null && getent group kvm | grep -qw "$(id -un)"; then
    sg kvm -c "$(printf '%q ' "${EMU_CMD[@]}")" >"$LOG" 2>&1 </dev/null &
else
    fail "no access to /dev/kvm (run: sudo gpasswd -a $(id -un) kvm)"
fi
EMU_PID=$!
booted=""
while (( $(date +%s) - START < BOOT_TIMEOUT )); do
    kill -0 "$EMU_PID" 2>/dev/null || fail "emulator exited during boot (see $LOG)"
    booted="$(timeout 5 "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    [[ "$booted" == "1" ]] && break
    sleep 3
done
[[ "$booted" == "1" ]] || fail "emulator did not finish booting within ${BOOT_TIMEOUT}s"
echo "==> Booted in $(( $(date +%s) - START ))s"

for pkg in "$APP_ID" "$APP_ID.test"; do
    adb_s uninstall "$pkg" >/dev/null 2>&1 || true
done
echo "==> Installing $APK (no test APK)"
adb_s install "$APK" >/dev/null || fail "adb install failed"
if adb_s shell pm list packages | tr -d '\r' | grep -qxF "package:$APP_ID.test"; then
    fail "the test APK is installed"
fi

echo "==> am start -W -n $APP_ID/$LAUNCHER"
adb_s shell am start -W -n "$APP_ID/$LAUNCHER" | tr -d '\r' | grep -q '^Status: ok' || fail "am start failed"
echo "==> Starting the :engine process (run-as am start-service; no PDF operation)"
start_out="$(adb_s shell run-as "$APP_ID" am start-service -n "$APP_ID/.engine.remote.EngineService" 2>&1 | tr -d '\r' || true)"
# On the API 31 ATD image the system refuses this (the service is unexported and `am` is not
# trusted from run-as); then only the UI-process check below applies.
echo "    $(grep -m1 -E 'Exception|Error|Starting' <<<"$start_out" || echo "${start_out:-<no output>}" | head -n1)"
sleep "$SETTLE_SECONDS"

ui_pid="$(adb_s shell pidof "$APP_ID" | tr -d '\r' || true)"
engine_pid="$(adb_s shell pidof "$APP_ID:engine" | tr -d '\r' || true)"
[[ "$ui_pid" =~ ^[0-9]+$ ]] || fail "UI process $APP_ID not running (pidof: '$ui_pid')"
echo "UI pid: $ui_pid  engine pid: ${engine_pid:-<not running>}"

maps_of() { adb_s shell run-as "$APP_ID" cat "/proc/$1/maps" | tr -d '\r'; }

ui_maps="$(maps_of "$ui_pid" || true)"
grep -q 'libc\.so' <<<"$ui_maps" || fail "could not read /proc/$ui_pid/maps via run-as ($(wc -l <<<"$ui_maps") lines)"
echo "UI process maps: $(wc -l <<<"$ui_maps") lines; libmupdf mappings: $(grep -c libmupdf <<<"$ui_maps" || true)"
if grep -q libmupdf <<<"$ui_maps"; then
    grep libmupdf <<<"$ui_maps" | head -n 5 >&2
    fail "the UI process maps libmupdf"
fi

if [[ "$engine_pid" =~ ^[0-9]+$ ]]; then
    engine_maps="$(maps_of "$engine_pid" || true)"
    grep -q 'libc\.so' <<<"$engine_maps" || fail "could not read /proc/$engine_pid/maps via run-as"
    if grep -q libmupdf <<<"$engine_maps"; then
        echo ":engine process maps libmupdf: yes"
    else
        echo ":engine process maps libmupdf: no (MuPDF loads on the first operation; none was run)"
    fi
    echo "oom_score_adj: UI=$(adb_s shell cat "/proc/$ui_pid/oom_score_adj" | tr -d '\r')" \
        "engine=$(adb_s shell cat "/proc/$engine_pid/oom_score_adj" | tr -d '\r')"
else
    echo ":engine process not running"
fi
echo "ISOLATION SMOKE OK"
