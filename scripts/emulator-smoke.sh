#!/usr/bin/env bash
# On-device smoke test: boots the `pdfutils-smoke` AVD headless, runs the instrumented tests
# (./gradlew connectedDebugAndroidTest) against it, and always shuts the emulator down.
# Prints "SMOKE OK" on success, "SMOKE FAIL: <reason>" and exits non-zero otherwise.
# Any installed copy of the app and its test APK is uninstalled first (a differently signed copy,
# e.g. left by release-smoke.sh, would otherwise make the install fail while Gradle still reports
# success), and the connected-test result XMLs must show at least one test and no failures/errors.
#
# `emulator-smoke.sh --check-results DIR` only runs that result check against DIR (no emulator).
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
AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"
RESULTS_DIR="$ROOT/app/build/outputs/androidTest-results/connected"
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

# Sums tests/failures/errors over the JUnit XMLs under $1; fails unless tests > 0 and no
# failures or errors. Prints "tests=N failures=F errors=E".
check_results() {
    local dir="$1" counts tests failures errors
    command -v python3 >/dev/null || fail "python3 is required to parse the test results"
    counts="$(python3 -I - "$dir" <<'PY'
import pathlib, sys
import xml.etree.ElementTree as ET
t = f = e = 0
for p in sorted(pathlib.Path(sys.argv[1]).rglob("*.xml")):
    root = ET.parse(p).getroot()
    suites = [root] if root.tag == "testsuite" else root.iter("testsuite")
    for s in suites:
        t += int(s.get("tests", 0)); f += int(s.get("failures", 0)); e += int(s.get("errors", 0))
print(t, f, e)
PY
)" || fail "could not parse test result XMLs under $dir"
    read -r tests failures errors <<<"$counts"
    echo "tests=$tests failures=$failures errors=$errors"
    (( tests > 0 )) || fail "no instrumented tests ran (no results under $dir)"
    (( failures == 0 && errors == 0 )) || fail "instrumented tests reported $failures failure(s), $errors error(s)"
}

if [[ "${1:-}" == "--check-results" ]]; then
    [[ -n "${2:-}" ]] || fail "usage: $0 --check-results DIR"
    check_results "$2"
    exit 0
fi

for tool in "$EMULATOR" "$ADB" "$AAPT2"; do
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

echo "==> ./gradlew assembleDebug assembleDebugAndroidTest"
./gradlew --console=plain assembleDebug assembleDebugAndroidTest || fail "building the debug APKs failed"
for apk_dir in app/build/outputs/apk/debug app/build/outputs/apk/androidTest/debug; do
    shopt -s nullglob
    apks=("$apk_dir"/*.apk)
    shopt -u nullglob
    (( ${#apks[@]} > 0 )) || fail "no APK in $apk_dir"
    for apk in "${apks[@]}"; do
        pkg="$("$AAPT2" dump packagename "$apk")" && [[ -n "$pkg" ]] || fail "aapt2 dump packagename failed for $apk"
        echo "==> Uninstalling any installed $pkg"
        out="$("$ADB" -s "$SERIAL" uninstall "$pkg" 2>&1 | tr -d '\r' || true)"
        echo "    ${out:-<no output>}"
    done
done

rm -rf "$RESULTS_DIR"

echo "==> ./gradlew connectedDebugAndroidTest"
ANDROID_SERIAL="$SERIAL" ./gradlew --console=plain connectedDebugAndroidTest \
    || fail "instrumented tests failed (report: app/build/reports/androidTests/connected/debug/index.html)"

check_results "$RESULTS_DIR"
echo "SMOKE OK"
