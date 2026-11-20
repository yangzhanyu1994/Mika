#!/usr/bin/env bash
set -euo pipefail

apk=${1:?Pass a signed Mika Fork APK}
arm64_apk=${2:?Pass the signed ARM64 APK}
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
android_jar="$ANDROID_HOME/platforms/android-36/android.jar"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir "$work/classes" "$work/dex"
javac --release 8 -cp "$android_jar" -d "$work/classes" tools/release/TaskRunArtProbe.java
"$build_tools/d8" --lib "$android_jar" --min-api 30 --output "$work/dex" \
    "$work/classes/com/wangzan101/mika/releaseprobe/TaskRunArtProbe.class"

cat > "$work/AndroidManifest.xml" <<'MANIFEST'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.wangzan101.mika.releaseprobe">
    <application android:hasCode="true" />
    <instrumentation android:name=".TaskRunArtProbe"
        android:targetPackage="com.wangzan101.mika.fork" />
</manifest>
MANIFEST
"$build_tools/aapt2" link -I "$android_jar" --manifest "$work/AndroidManifest.xml" \
    --min-sdk-version 30 --target-sdk-version 35 -o "$work/unsigned.apk"
zip -q -j "$work/unsigned.apk" "$work/dex/classes.dex"
"$build_tools/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
"$build_tools/apksigner" sign --ks "$MIKA_FORK_KEYSTORE" --ks-type PKCS12 \
    --ks-key-alias "$MIKA_FORK_KEY_ALIAS" --ks-pass env:MIKA_FORK_STORE_PASSWORD \
    --key-pass env:MIKA_FORK_KEY_PASSWORD --out "$work/probe.apk" "$work/aligned.apk"

test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = 36
adb install "$apk"
adb install "$work/probe.apk"
output=$(timeout 90 adb shell am instrument -w com.wangzan101.mika.releaseprobe/.TaskRunArtProbe)
printf '%s\n' "$output"
printf '%s\n' "$output" | tr -d '\r' | grep -Fx 'INSTRUMENTATION_RESULT: artVerified=true'
