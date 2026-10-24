#!/usr/bin/env bash
set -Eeuo pipefail

cd "$(dirname "$0")"
MODE="${1:-check}"
if [[ "$MODE" != "check" && "$MODE" != "publish" ]]; then
    echo "Verwendung: ./release-test.sh [check|publish]"
    exit 1
fi

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
if [[ "$MODE" == "check" ]]; then
    echo "TESTMODUS: Es wurde nichts veröffentlicht."
    exit 0
fi

echo "=== Veröffentlichungsprüfung ==="

[[ "$(git branch --show-current)" == "test" ]] || {
    echo "Abbruch: Nur Branch test darf Prereleases veröffentlichen."
    exit 1
}

[[ -z "$(git status --porcelain -- . ':(exclude)release.sh')" ]] || {
    echo "Abbruch: Testzweig enthält ungesicherte Änderungen."
    exit 1
}

SHA=$(git rev-parse HEAD)
REMOTE_SHA=$(git ls-remote origin refs/heads/test | cut -f1)

[[ -n "$REMOTE_SHA" && "$SHA" == "$REMOTE_SHA" ]] || {
    echo "Abbruch: Testzweig ist nicht mit GitHub synchron."
    exit 1
}

[[ "$CODE" -gt 1006 ]] || {
    echo "Abbruch: Erst ab Version 0.1.7 veröffentlichen."
    exit 1
}

if [[ -n "$(git ls-remote --tags origin "refs/tags/$TAG")" ]]; then
    echo "Abbruch: Git-Tag existiert bereits."
    exit 1
fi

if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    echo "Abbruch: GitHub-Release existiert bereits."
    exit 1
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

cp "$APK" "$TMP/FunkGateway-Mobile.apk"

echo "=== GitHub-Prerelease $TAG ==="

gh release create "$TAG" "$TMP/FunkGateway-Mobile.apk" \
    --repo "$REPO" \
    --target "$SHA" \
    --prerelease \
    --title "FunkGateway Mobile $VERSION – Testversion" \
    --notes "Interne Testversion von FunkGateway Mobile. Nicht als stabile Version freigegeben."

echo "OK: GitHub-Prerelease veröffentlicht."
echo "https://github.com/$REPO/releases/tag/$TAG"
