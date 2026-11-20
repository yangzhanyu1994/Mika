#!/usr/bin/env bash
set -euo pipefail

apk=${1:?Pass a signed Mika Fork APK}
arm64_apk=${2:?Pass the signed ARM64 APK}
test_apk=${3:?Pass the forkRelease instrumentation APK}
python3 - "$apk" "$arm64_apk" <<'VERIFY_DEX'
import hashlib
import re
import sys
import zipfile

def dex_digests(path):
    with zipfile.ZipFile(path) as apk:
        return {
            name: hashlib.sha256(apk.read(name)).hexdigest()
            for name in apk.namelist() if re.fullmatch(r"classes[0-9]*[.]dex", name)
        }

universal = dex_digests(sys.argv[1])
if not universal or universal != dex_digests(sys.argv[2]):
    sys.exit("ARM64 and universal APKs have different DEX contents")
print("ARM64 and universal APKs have identical DEX contents")
VERIFY_DEX
build_tools="$ANDROID_HOME/build-tools/35.0.0"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

"$build_tools/apksigner" sign --ks "$MIKA_FORK_KEYSTORE" --ks-type PKCS12 \
    --ks-key-alias "$MIKA_FORK_KEY_ALIAS" --ks-pass env:MIKA_FORK_STORE_PASSWORD \
    --key-pass env:MIKA_FORK_KEY_PASSWORD --out "$work/test.apk" "$test_apk"

test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = 36
adb install "$apk"
adb install "$work/test.apk"
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
if ! timeout 150 adb shell am instrument -w \
    -e class com.wangzan101.mika.task.TaskStartupDeviceTest,com.wangzan101.mika.ui.MikaVoiceCallDeviceTest \
    com.wangzan101.mika.fork.test/androidx.test.runner.AndroidJUnitRunner | tee "$work/result.log" ||
    ! tr -d '\r' < "$work/result.log" | grep -Fx 'OK (2 tests)'; then
    adb logcat -b crash -d
    exit 1
fi
