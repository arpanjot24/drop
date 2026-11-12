#!/bin/sh
# Builds Drop.apk without Gradle or the Android SDK.
# Needs: JDK, and `apt install aapt apksigner zipalign dalvik-exchange`.
# android.jar: any Android 10+ framework jar (here: org.robolectric:android-all from Maven Central).
set -e
cd "$(dirname "$0")"
unset JAVA_TOOL_OPTIONS
V="${VERSION_CODE:-2}"   # releases are tagged v$V; the app updates when it sees a higher number
rm -rf build && mkdir -p build/classes
aapt2 compile --dir res -o build/res.zip
aapt2 link -o build/base.apk -I android.jar --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 29 --target-sdk-version 34 --version-code "$V" --version-name "$V" build/res.zip
javac -nowarn --release 8 -cp android.jar -d build/classes src/app/drop/*.java
dalvik-exchange --dex --min-sdk-version=26 --output=build/classes.dex build/classes
(cd build && zip -q base.apk classes.dex && zipalign -f 4 base.apk aligned.apk)
[ -f drop.keystore ] || keytool -genkeypair -keystore drop.keystore -storepass dropdrop -alias drop \
  -keyalg RSA -keysize 2048 -validity 10000 -dname CN=Drop
apksigner sign --ks drop.keystore --ks-pass pass:dropdrop --out Drop.apk build/aligned.apk
apksigner verify --verbose Drop.apk | head -4
ls -la Drop.apk
