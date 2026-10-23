#!/usr/bin/env bash
set -Eeuo pipefail

MODE="${1:-}"
REPO="MysticFire01/FunkGateway-Mobil"
APK="app/build/outputs/apk/debug/app-debug.apk"
ASSET="FunkGateway-Mobile.apk"
EXPECTED_CERT="6e47703f89878ea6c071aa7cdd895949550f2cd82204515923a815d122bc4498"

if [[ "$MODE" != "current" && "$MODE" != "patch" ]]; then
    echo "Verwendung: ./release.sh current|patch"
    exit 1
fi

cd "$(dirname "$0")"

for cmd in git gh python3; do
    command -v "$cmd" >/dev/null || {
        echo "Fehlt: $cmd"
        exit 1
    }
done

[[ "$(git branch --show-current)" == "main" ]] || {
    echo "Abbruch: Nicht auf Branch main."
    exit 1
}

[[ -z "$(git diff --cached --name-only)" ]] || {
    echo "Abbruch: Bereits vorgemerkte Git-Änderungen vorhanden."
    exit 1
}

[[ -z "$(git ls-files --others --exclude-standard | grep -v '^release.sh$' || true)" ]] || {
    echo "Abbruch: Unbekannte Dateien im Repository vorhanden."
    exit 1
}

git fetch origin main --tags
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || {
    echo "Abbruch: Lokaler main und GitHub unterscheiden sich."
    exit 1
}

export ANDROID_HOME="/daten/Android/sdk"
export GRADLE_USER_HOME="/daten/Android/gradle-cache"

if [[ "$MODE" == "patch" ]]; then
    python3 - <<'PY'
from pathlib import Path
import re

p = Path("app/build.gradle.kts")
s = p.read_text()

m = re.search(r'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"', s)
if not m:
    raise SystemExit("Versionsnummer nicht gefunden.")

major, minor, patch = map(int, m.groups())
new_version = f"{major}.{minor}.{patch + 1}"
new_code = major * 1000000 + minor * 1000 + patch + 1

s, n1 = re.subn(
    r'versionName\s*=\s*"\d+\.\d+\.\d+"',
    f'versionName = "{new_version}"', s, count=1
)
s, n2 = re.subn(
    r'versionCode\s*=\s*\d+',
    f'versionCode = {new_code}', s, count=1
)
if n1 != 1 or n2 != 1:
    raise SystemExit("Versionsfelder nicht eindeutig.")

p.write_text(s)
print(f"Neue Version: {new_version}")
PY
fi

VERSION=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)
CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
TAG="v${VERSION}"

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    echo "Ungültige Versionsnummer."
    exit 1
}

EXPECTED_CODE=$(python3 - "$VERSION" <<'PY'
import sys
a, b, c = map(int, sys.argv[1].split("."))
print(a * 1000000 + b * 1000 + c)
PY
)

[[ "$CODE" == "$EXPECTED_CODE" ]] || {
    echo "VersionCode stimmt nicht zur Versionsnummer."
    exit 1
}

if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "Abbruch: Tag $TAG existiert bereits."
    exit 1
fi

if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    echo "Abbruch: GitHub-Release $TAG existiert bereits."
    exit 1
fi

echo "=== FunkGateway Mobile $VERSION ==="
echo "=== Android-Build und Tests ==="
./gradlew clean assembleDebug testDebugUnitTest

AAPT="$ANDROID_HOME/build-tools/35.0.0/aapt"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"

"$APKSIGNER" verify --print-certs "$APK" > /tmp/fgmobile-apk-signature.txt

CERT=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' /tmp/fgmobile-apk-signature.txt)
[[ "$CERT" == "$EXPECTED_CERT" ]] || {
    echo "Abbruch: APK-Signatur stimmt nicht!"
    exit 1
}

PACKAGE_INFO=$("$AAPT" dump badging "$APK" | grep '^package:')

[[ "$PACKAGE_INFO" == *"name='org.funkgateway.mobile'"* &&
   "$PACKAGE_INFO" == *"versionCode='$CODE'"* &&
   "$PACKAGE_INFO" == *"versionName='$VERSION'"* ]] || {
    echo "Abbruch: APK-Version oder Paketname falsch."
    exit 1
}

echo "=== APK erfolgreich geprüft ==="

# Alle nicht ignorierten Projektänderungen vormerken.
git add -A

# Vor dem Commit unerwünschte oder sensible Dateien abfangen.
python3 - <<'CHECK'
import subprocess
from pathlib import Path

files = subprocess.check_output(
    ["git", "diff", "--cached", "--name-only", "-z"]
).decode().split("\0")

blocked_extensions = {
    ".apk", ".aab", ".jks", ".keystore", ".p12", ".pfx",
    ".pem", ".key", ".env", ".bak", ".orig", ".swp"
}
blocked_names = {
    "local.properties", "keystore.properties",
    "signing.properties", "secrets.properties",
    "google-services.json"
}

blocked = []
for name in filter(None, files):
    path = Path(name)
    if (
        path.suffix.lower() in blocked_extensions
        or path.name.lower() in blocked_names
        or path.name.lower().startswith(".env")
        or "build" in path.parts
        or ".gradle" in path.parts
    ):
        blocked.append(name)

if blocked:
    raise SystemExit(
        "Abbruch: Unerwünschte Dateien vorgemerkt:\\n" +
        "\\n".join(blocked)
    )

print("OK: Git-Dateiauswahl geprüft.")
CHECK

[[ -n "$(git diff --cached --name-only)" ]] || {
    echo "Abbruch: Keine Änderungen für Release vorhanden."
    exit 1
}

git commit -m "FunkGateway Mobile $VERSION"
git tag -a "$TAG" -m "FunkGateway Mobile $VERSION"

git push origin main
git push origin "$TAG"

TMPDIR_RELEASE=$(mktemp -d)
trap 'rm -rf "$TMPDIR_RELEASE"' EXIT
cp "$APK" "$TMPDIR_RELEASE/$ASSET"

PREVIOUS_TAG=$(git tag --list 'v*' --sort=-version:refname |
    grep -Fxv "$TAG" | head -n 1 || true)

NOTES_FILE="$TMPDIR_RELEASE/notes.md"

{
    echo "FunkGateway Mobile $VERSION"
    echo
    echo "Änderungen:"
    if [[ -n "$PREVIOUS_TAG" ]]; then
        git log --pretty='- %s' "$PREVIOUS_TAG..HEAD"
    else
        git log -1 --pretty='- %s'
    fi
    echo
    echo "Android-APK mit geprüfter Paketversion und Signatur."
} > "$NOTES_FILE"

echo "=== GitHub-Release erstellen ==="

gh release create "$TAG" "$TMPDIR_RELEASE/$ASSET" \
    --repo "$REPO" \
    --title "FunkGateway Mobile $VERSION" \
    --notes-file "$NOTES_FILE"

echo
echo "ERFOLGREICH: FunkGateway Mobile $VERSION veröffentlicht!"
echo "https://github.com/$REPO/releases/tag/$TAG"
