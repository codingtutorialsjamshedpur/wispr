#!/usr/bin/env bash
# Wispr v4.1.1 one-shot fix. In Codespaces terminal (repo root):
#   git pull && unzip -o wispr-fix.zip && bash wispr-fix/apply.sh
# Add --no-release to only push the code (no v4.1.1 tag / no automatic release).
main() {
  set -e
  cd "$(dirname "$0")/.."
  [ -f settings.gradle.kts ] || { echo "Run this inside the ALWAYSWISPR repo"; exit 1; }
  echo "== 1/4 Applying the fix"
  python3 wispr-fix/apply_fix.py
  echo "== 2/4 Removing the fix kit from the repo"
  git rm -r -q --cached --ignore-unmatch wispr-fix wispr-fix.zip >/dev/null 2>&1 || true
  rm -f wispr-fix.zip
  echo "== 3/4 Commit + push"
  git add -A -- . ':!wispr-fix'
  git commit -q -m "Wispr v4.1.1: real update check + Check now refresh, resizable/movable mini window" || echo "(nothing new to commit)"
  git push
  if [ "$1" != "--no-release" ]; then
    echo "== 4/4 Tag v4.1.1 -> GitHub Actions builds the APK and puts it on the Releases page"
    if git rev-parse -q --verify refs/tags/v4.1.1 >/dev/null || git ls-remote --exit-code --tags origin v4.1.1 >/dev/null 2>&1; then
      echo "Tag v4.1.1 already exists. Bump wispr.version in gradle.properties and tag a new one (e.g. v4.1.2)."
    else
      git tag v4.1.1 && git push origin v4.1.1
    fi
  fi
  rm -rf wispr-fix
  echo
  echo "Done. Open the Actions tab: 'Build APK and publish release' is running. In ~5-8 min v4.1.1 is on Releases."
}
main "$@"; exit
