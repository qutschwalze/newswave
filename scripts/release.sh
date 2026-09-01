#!/usr/bin/env bash
# News Wave Release-Script
# Usage: scripts/release.sh <versionName>   (z. B. 0.5.1)
# Voraussetzung: versionName/versionCode in app/build.gradle.kts sind bereits erhöht
# und committed. Baut die APK, taggt v<version>, pusht und erstellt das
# GitHub-Release mit der APK als Asset. Token kommt aus ~/.git-credentials
# (wird niemals ins Repo geschrieben).
set -euo pipefail

VERSION="${1:?Usage: release.sh <versionName> (z. B. 0.5.1)}"
REPO="qutschwalze/newswave"

cd "$(dirname "$0")/.."

# 1) Bauen
./gradlew assembleDebug
APK="app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"  # ABI-Split (s. build.gradle.kts)
[ -f "$APK" ] || APK="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "APK nicht gefunden (arm64-v8a Split oder app-debug.apk)"; exit 1; }
echo "APK: $APK"

# 2) Tag + Push
git tag -a "v$VERSION" -m "News Wave v$VERSION" 2>/dev/null || echo "Tag v$VERSION existiert bereits"
git push origin "v$VERSION"

# 3) Release erstellen + APK hochladen (Token nie ausgeben)
TOKEN=$(grep -oP '^https://[^:]+:\K[^@]+' "${HOME:-/root}/.git-credentials" | head -1)
[ -n "$TOKEN" ] || { echo "Kein Token in ~/.git-credentials"; exit 1; }

BODY=$(cat <<EOF
{
  "tag_name": "v$VERSION",
  "name": "News Wave v$VERSION",
  "body": "Aktuelle APK, siehe Commits / Latest APK, see commit history.\\n\\n- Deutsch: Über die vorherige Version installieren\\n- English: Install over the previous version",
  "draft": false,
  "prerelease": false
}
EOF
)

RELEASE_ID=$(curl -sf -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -d "$BODY" \
  "https://api.github.com/repos/$REPO/releases" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")

curl -sf -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@$APK" \
  "https://uploads.github.com/repos/$REPO/releases/$RELEASE_ID/assets?name=NewsWave-v$VERSION.apk" \
  > /dev/null

echo "✅ Release v$VERSION veröffentlicht: https://github.com/$REPO/releases/tag/v$VERSION"
