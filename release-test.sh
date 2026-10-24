#!/usr/bin/env bash
set -Eeuo pipefail

cd "$(dirname "$0")"

REPO="MysticFire01/FunkGateway-Mobil"
APK="app/build/outputs/apk/debug/app-debug.apk"
EXPECTED_CERT="6e47703f89878ea6c071aa7cdd895949550f2cd82204515923a815d122bc4498"

export ANDROID_HOME="/daten/Android/sdk"
export GRADLE_USER_HOME="/daten/Android/gradle-cache"

VERSION=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)
CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
TAG="v${VERSION}"

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    echo "Abbruch: Ungültige Version."
    exit 1
}

EXPECTED_CODE=$(python3 - "$VERSION" <<'PY'
import sys
major, minor, patch = map(int, sys.argv[1].split("."))
print(major * 1000000 + minor * 1000 + patch)
PY
)

[[ "$CODE" == "$EXPECTED_CODE" ]] || {
    echo "Abbruch: Versionscode stimmt nicht."
    exit 1
}

echo "=== Test-Release $VERSION ==="
echo "=== Build und Tests ==="

./gradlew assembleDebug testDebugUnitTest

APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
AAPT="$ANDROID_HOME/build-tools/35.0.0/aapt"

"$APKSIGNER" verify --print-certs "$APK" > /tmp/fgmobile-test-signature.txt
CERT=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' /tmp/fgmobile-test-signature.txt)

[[ "$CERT" == "$EXPECTED_CERT" ]] || {
    echo "Abbruch: APK-Signatur stimmt nicht."
    exit 1
}

INFO=$("$AAPT" dump badging "$APK" | grep '^package:')

[[ "$INFO" == *"name='org.funkgateway.mobile'"* &&
   "$INFO" == *"versionCode='$CODE'"* &&
   "$INFO" == *"versionName='$VERSION'"* ]] || {
    echo "Abbruch: APK-Metadaten stimmen nicht."
    exit 1
}

echo
echo "OK: APK erfolgreich gebaut und geprüft."
echo "Geplantes GitHub-Prerelease: $TAG"
echo "Repository: $REPO"
echo "APK: $APK"
echo
echo "TESTMODUS: Es wurde nichts veröffentlicht."
