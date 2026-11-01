Wispr v4.1.1 fix kit (by ClickUp Brain)

WHAT IT FIXES
1. "Update available" no longer shows when you already have the latest APK
   (compares the APK's SHA-256 with the release + ignores "-build.N" of test builds).
2. Settings > Updates: "Check now" always asks GitHub fresh, separate orange "Update"
   button only for a real update, toast after every check, old banner/notification cleared.
3. Mini window (hold the bubble): smaller by default, drag the orange corner to resize,
   drag the orange bar on top to move, double-tap the bar to reset, size button (S/M/L) in the header.
4. You can see what you type/say: window jumps above the keyboard, voice text lands in the
   chat box (not auto-sent) with a live "Listening 0:05" timer.

HOW (3 steps)
1. GitHub repo page > "Add file" (+) > Upload files > drop wispr-fix.zip > Commit changes.
2. Code > Codespaces > open your codespace. In the terminal:
      git pull && unzip -o wispr-fix.zip && bash wispr-fix/apply.sh
   It patches, commits, pushes, and pushes tag v4.1.1.
3. Actions tab: the tag build makes the signed APK and publishes the v4.1.1 release by itself.
   (No tag? Run: bash wispr-fix/apply.sh --no-release, then Actions > Run workflow,
    download the APK from Artifacts and upload it to a release yourself.)

NOTES
- Tag builds need the 4 signing secrets (WISPR_KEYSTORE_BASE64, WISPR_KEYSTORE_PASSWORD,
  WISPR_KEY_ALIAS, WISPR_KEY_PASSWORD). Without them every APK has a different key and the
  in-app update can't install on top.
- If a step says "expected 1 match, found 0" the file was edited after v4.1.0: nothing is
  committed, send me the file and I'll redo it.
