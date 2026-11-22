#!/usr/bin/env bash
# On-device smoke test: boots the `pdfutils-smoke` AVD headless, runs the instrumented tests
# (./gradlew connectedDebugAndroidTest) against it, and always shuts the emulator down.
# Prints "SMOKE OK" on success, "SMOKE FAIL: <reason>" and exits non-zero otherwise.
#
# Environment overrides: ANDROID_HOME, JAVA_HOME, AVD_NAME (default pdfutils-smoke),
# BOOT_TIMEOUT seconds (default 300), EMULATOR_PORT (default 5584).
# Host libraries the emulator needs even headless (Ubuntu):
#   sudo apt-get install libx11-xcb1 libpulse0 libxkbfile1 libgl1
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

AVD_NAME="${AVD_NAME:-pdfutils-smoke}"
SYSTEM_IMAGE="system-images;android-31;aosp_atd;x86_64"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"
PORT="${EMULATOR_PORT:-5584}"
SERIAL="emulator-$PORT"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"
LOG="$(mktemp -t pdfutils-emulator.XXXXXX.log)"
EMU_PID=""

fail() {
    echo "SMOKE FAIL: $*" >&2
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
        # sg/emulator wrappers may leave the qemu child running under another parent.
        pkill -f -- "qemu-system.*-avd $AVD_NAME" >/dev/null 2>&1 || true
        echo "==> Emulator stopped (log: $LOG)"
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

for tool in "$EMULATOR" "$ADB"; do
    [[ -x "$tool" ]] || fail "required tool not found: $tool (install with: sdkmanager emulator platform-tools)"
done

if ! "$EMULATOR" -list-avds 2>/dev/null | grep -qxF "$AVD_NAME"; then
    cat >&2 <<EOF
AVD '$AVD_NAME' does not exist. Create it with:
  export ANDROID_HOME=$ANDROID_HOME
  yes | sdkmanager --licenses
  sdkmanager --install emulator "$SYSTEM_IMAGE"
  echo no | avdmanager create avd -n $AVD_NAME -k "$SYSTEM_IMAGE" -d pixel_6 --force
  sudo gpasswd -a \$USER kvm   # then log in again, or this script uses 'sg kvm'
EOF
    fail "AVD '$AVD_NAME' not found"
fi

if "$ADB" devices | grep -q "^$SERIAL[[:space:]]"; then
    fail "an emulator is already running on port $PORT ($SERIAL); stop it or set EMULATOR_PORT"
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

echo "==> ./gradlew connectedDebugAndroidTest"
ANDROID_SERIAL="$SERIAL" ./gradlew --console=plain connectedDebugAndroidTest \
    || fail "instrumented tests failed (report: app/build/reports/androidTests/connected/debug/index.html)"

echo "SMOKE OK"
