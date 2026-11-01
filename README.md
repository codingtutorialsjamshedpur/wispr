# Wispr

A fork of [Wispr by Deepu Gupta](https://github.com/DeepuExplainsAi/ALWAYSWISPR) (upstream), maintained in this repository. Original authorship and copyright stay with CTJ under the [Apache License, Version 2.0](LICENSE) — see also [NOTICE](NOTICE).




https://github.com/user-attachments/assets/a830ca98-6e18-4123-a721-9fabcec612f3






<p align="center">
  <a href="https://github.com/codingtutorialsjamshedpur/wispr/releases/latest">
    <img src="https://img.shields.io/badge/ANDROID%20APK%20%7C%20DOWNLOAD-34A853?style=for-the-badge&logo=android&logoColor=white" alt="Download Wispr Android APK">
  </a>
</p>


Speak in Hinglish, Hindi, English or 25+ languages, and Wispr types it into **any app**, over **any keyboard**.
Open source under the **Apache License 2.0**. Copyright 2026 Deepu Gupta.

- **Bubble over every keyboard.** Tap the orange orb, talk, tap ✓. Text is typed where your cursor is (✕ cancels).
- **Never deletes your notes.** Wispr only *adds* text. It never replaces what's already in Notes, Keep, Docs or Word.
- **Hold the bubble: mini window.** Write / Polish / Translate, target language, Hindi ⇄ Hinglish, AI models, **AI chat**, **Understand screen**.
- **Understand any screen in one tap.** Reads the text on screen in any language and explains or translates it. **Scan picture (OCR)** reads text inside images, videos and PDFs (Android 11+). Works from any app via Android's Accessibility button/shortcut.
- **AI chat anywhere.** Type or speak; tap **Insert** to drop the answer into the box you were in.
- **Transcription workspace.** Transcribe without dictation: **(A)** live capture of eligible internal/system playback from other apps (Android 10+, `AudioPlaybackCapture`, MediaProjection consent, own foreground service), **(B)** import a local audio/video file the phone can decode (MP3, WAV, M4A/AAC, OGG/Opus, FLAC, video soundtracks), **(C)** direct mic sessions. Original spoken text, auto-detected language and timed per-segment confidence are preserved; failed chunks keep their audio and are retried on demand. Translation stays a separate, explicit action.
- **Hinglish by default.** Hindi toggle OFF = always Roman Hinglish ("kal meeting hai"). ON = Devanagari.
- **Your own free Groq key.** Stored with Android Keystore (AES-256-GCM). No Wispr server, no account, no tracking.

| Use | Model | Notes |
| --- | --- | --- |
| Speech | `whisper-large-v3` (default), `whisper-large-v3-turbo` | 20 req/min, 2,000/day, 8 h audio/day |
| Polish / translate / chat | `openai/gpt-oss-120b` (default), `openai/gpt-oss-20b` | 30 req/min, 1,000/day |
| Chat + screen OCR (vision) | `qwen/qwen3.8-27b`, `qwen/qwen3.6-27b` | Preview |
| Chat with live web search | `groq/compound`, `groq/compound-mini` | 250/day |

"Check what's live on Groq" in the mini window's **Models** tab lists every model your key can use, including new ones. Add a model permanently with one line in `Models.kt`.

## Requirements

- Android **8.0 (API 26)** or newer. OCR screen scan needs Android 11+. Live internal-playback capture needs Android 10+.
- Build: AGP 9.4.0, Gradle 9.6.0, JDK 21, compileSdk/targetSdk **36**, minSdk **26**, Kotlin 2.4.10 (built into AGP 9).

## Build and release (GitHub Actions)

1. **Signing key, once** (required for releases, so phones can update in place):
   ```
   keytool -genkeypair -v -keystore wispr.jks -alias wispr -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 wispr.jks > wispr.jks.b64
   ```
   In GitHub: **Settings → Secrets and variables → Actions** add `WISPR_KEYSTORE_BASE64` (contents of `wispr.jks.b64`), `WISPR_KEYSTORE_PASSWORD`, `WISPR_KEY_ALIAS` (`wispr`), `WISPR_KEY_PASSWORD`. Back up `wispr.jks` safely: lose it and nobody can update again.
2. **Release**: bump `wispr.version` in `gradle.properties`, write `RELEASE_NOTES.md`, then `git tag v4.1.0 && git push origin v4.1.0`. The signed APK + `SHA256SUMS.txt` land on the **Releases** page. Tags with a dash (`v4.2.0-beta.1`) become pre-releases.
3. Test build without releasing: **Actions → Build APK and publish release → Run workflow** (APK under *Artifacts*).

## In-app updates

Every installed Wispr checks this repo's latest GitHub Release (app open: every 6 h, keyboard bubble: every 12 h, with ETag caching and a fallback when GitHub's 60 requests/hour limit is hit). When a newer tag is out:

- The app shows an **update card** with the release notes and **Update now**; outside the app a notification appears.
- **Auto** (default): it downloads on Wi-Fi, checks the SHA-256 and that the APK is the same app signed with the same key, then one tap installs it. On Android 12+ it installs without the extra Android prompt; Android 8 to 11 show the normal "Update" dialog. Settings and the Groq key stay.
- First time only, Android asks to allow **Install unknown apps** for Wispr.
- If an APK is signed with a different key (for example an old debug-signed build), Wispr says so and opens the GitHub page: uninstall once, install the new APK, and from then on updates work in-app.
- The repo is baked in from `github.repository` at build time, so forks check their own Releases. Local builds: set `wispr.repo=owner/name` in `gradle.properties` (for this fork: `wispr.repo=codingtutorialsjamshedpur/wispr`).
- **Google Play build**: set `wispr.selfUpdate=false` and remove `REQUEST_INSTALL_PACKAGES` and `UPDATE_PACKAGES_WITHOUT_USER_ACTION` from the manifest (Play doesn't allow self-updating apps).
- Android developer verification: as of 30 Sep 2026 it's enforced only for 7 app stores in Brazil, Indonesia, Singapore and Thailand; direct GitHub APKs aren't covered yet. Google plans a global rollout in 2027, so register the package in the Android Developer Console before then.

## Code map

| File | What it does |
| --- | --- |
| `BubbleService.kt` | Accessibility service: bubble, gestures, recording, Accessibility button |
| `BubbleView.kt` | Orange orb + ✕ / waveform / ✓ pill (Canvas) |
| `Inserter.kt` | Safe typing into other apps (paste only, never overwrites) |
| `Panel.kt` + `assets/web/panel.html` | Floating mini window |
| `Assistant.kt` | AI chat, Understand screen, voice-to-chat |
| `ScreenReader.kt` | On-screen text + screenshot for OCR |
| `Engine.kt` | Dictation pipeline: record → Whisper → Hinglish / Polish / Translate, auto-retry |
| `Groq.kt`, `Models.kt`, `Prompts.kt` | Groq API client, model catalog, prompts |
| `Store.kt`, `Crypto.kt` | Encrypted settings + history |
| `Translit.java` | Offline Hindi → Hinglish fallback |
| `Updater.kt`, `UpdateReceiver.kt` | In-app updates from GitHub Releases: check, download, verify, install |
| `TranscriptionManager.kt` | Universal transcript sessions (system capture / imported files / mic): ordered merging, retries, honest failure reporting |
| `PlaybackCapture.kt`, `PlaybackCaptureService.kt`, `CaptureConsentActivity.kt` | Live internal-playback capture engine, its foreground service, and the MediaProjection consent gate |
| `Chunker.kt`, `Decode.kt` | Loss-less 16 kHz WAV chunking and local media file decode for imports |

## Project structure

```
whisper_ai_alternative/
├── app/
│   ├── build.gradle.kts              # App module: versions, signing env, buildConfig
│   ├── proguard-rules.pro            # R8/minify release rules
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml   # Permissions, launcher, accessibility + services
│       │   ├── assets/               # Licenses, floating panel web assets (panel.html)
│       │   ├── java/com/deepugupta/wispr/   # All Kotlin sources (see Code map)
│       │   └── res/                  # Icons, colors, themes, accessibility config
│       └── (androidTest not present; unit-test sources not included)
├── wispr-fix/                        # v4.1.1 fix toolkit: apply.sh, apply_fix.py, patched web assets
├── dist/                             # Local build outputs (gitignored)
├── .github/workflows/release.yml     # Tag-driven sign + build + publish APK releases
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── gradle/ (wrapper: 9.6.0) + gradlew / gradlew.bat
├── README.md · LICENSE (Apache-2.0) · NOTICE · RELEASE_NOTES.md
└── BUILD_REPORT.md · TEST_RESULTS.md # Local build & audit documentation
```

## Testing status

The current debug APK builds successfully and the transcription pipeline has been statically audited (see [TEST_RESULTS.md](TEST_RESULTS.md) and [BUILD_REPORT.md](BUILD_REPORT.md)). **Real-device runtime testing has not yet run** — all on-device verification rows in `TEST_RESULTS.md` remain `NOT RUN`. Please re-test on a physical device before relying on the capture features.

## Privacy

- Update checks only ask GitHub for this repo's latest release (no personal data, no key). Turn them off in Settings → Updates.

Audio, and screen text when **you** tap Understand, go only to Groq over HTTPS, using your key. Wispr never reads what you type, and it never sends your data on its own.

## License

Copyright 2026 Deepu Gupta. Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE).
Forks are welcome under the licence. Please use your own app name and icon (Apache 2.0 §6 does not grant trademark rights).
