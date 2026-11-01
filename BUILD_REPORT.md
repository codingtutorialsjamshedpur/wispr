# Wispr — Local Debug Build Report

## Repository
- **Repo:** https://github.com/DeepuExplainsAi/ALWAYSWISPR.git
- **Branch:** `main` (tracking `origin/main`)
- **Commit:** `fbe5f3e` — "Wispr v4.1.4: latest changes"
- **Local path:** `C:\Users\shour\Desktop\flutter practise\whisper_ai_alternative`
- The target folder pre-contained an empty, uninitialized `.git` (no commits/remotes/user files).
  It was reused instead of deleted: the remote was added, fetched, and `main` checked out.

## Toolchain
| Component | Version | Source |
| --- | --- | --- |
| JDK | OpenJDK 21.0.13+9 (Temurin, portable) | Adoptium API, SHA-256 verified (`a4dbaa05…2004f9`) |
| Gradle | 9.6.0 (wrapper generated) | services.gradle.org, SHA-256 verified (`bbaeb2fe…a529a01`) |
| Android Gradle Plugin | 9.4.0 | `build.gradle.kts` (unchanged) |
| Kotlin | 2.4.10 (built into AGP 9) | `build.gradle.kts` classpath (unchanged) |
| Android SDK | Located at `C:\Users\shour\AppData\Local\Android\Sdk` | pre-existing |
| platform / build-tools | android-36 / 36.0.0 / platform-tools 37.0.1 | pre-existing, already license-accepted |

JDK and Gradle live in `C:\Users\shour\.wispr-tools\` and are **session-configured only**
(`JAVA_HOME`/`PATH` set per PowerShell session). No global environment variables were changed.

## SDK version reconciliation
- `README.md` and comments in `build.gradle.kts` *document* compileSdk/targetSdk 37.
- Actual code (`app/build.gradle.kts`): `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`.
- CI (`.github/workflows/release.yml`) installs exactly `platforms;android-36 build-tools;36.0.0`.
- **Conclusion:** the "37" is a stale comment only; API 36 is what the project builds with.
  The existing configuration (36/36/26) was preserved — no SDK changes made.
  Confirmed in the built APK: `compileSdkVersion='36'`, `targetSdkVersion:'36'`, `minSdkVersion:'26'`.

## Build command
```powershell
$env:JAVA_HOME = "C:\Users\shour\.wispr-tools\jdk-21.0.13+9"
.\gradlew.bat :app:assembleDebug "-Pwispr.selfUpdate=false"
```
- Result: **BUILD SUCCESSFUL** (36 tasks executed)
- `wispr.selfUpdate=false` disables the in-app GitHub updater for this local test build
  (functionality untouched; set via Gradle property only, no source modified).
- Note: on Windows PowerShell the `-P…` flag must be quoted (`"-Pwispr.selfUpdate=false"`)
  or PowerShell splits it and Gradle reports a bogus task name.

## Build warnings (non-fatal)
- Transient Kotlin-daemon RMI failure ("Connection reset"); Gradle automatically compiled
  without the daemon. One-time occurrence, no task impact.
- `Inserter.kt:97:49` — unnecessary non-null assertion (`!!`) on a non-null `String`. Upstream code, left as-is.

## APK
- **Output:** `app\build\outputs\apk\debug\app-debug.apk`
- **Convenient copy:** `dist\Wispr-debug.apk`
- **Checksum online:** `dist\SHA256SUMS.txt`
- **Size:** 2,271,247 bytes (~2.2 MB)
- **SHA-256:** `b9948d6795f8c5278f57124e4d5ba2ed78e20bd21d999c8052bd98009b205b88`
- **Application ID:** `com.deepugupta.wispr`
- **Version:** 4.1.4 (versionCode 40104) — from `gradle.properties`, untouched
- **Signing:** Android debug key (no release keystore created — per task rules)

## Release APK (additional build)
- **Command:** `.\gradlew.bat :app:assembleRelease "-Pwispr.selfUpdate=false"`
- **Result:** BUILD SUCCESSFUL in 52s (42 tasks; R8 minify + resource shrinking ran, as configured upstream)
- **Output:** `app\build\outputs\apk\release\app-release.apk` → copy: `dist\Wispr-release.apk`
- **Size:** 377,741 bytes (~0.37 MB — R8 suggests much smaller than the debug APK)
- **SHA-256:** `16b6a7c91f6ea5d8087aef1133b3adcb84b59829af1be82dc7c7c3181ae29e4c`
- **Application ID / version:** `com.deepugupta.wispr` / 4.1.4 (versionCode 40104) — verified with aapt2
- **Signing:** no keystore provided, so the upstream build script fell back to the Android debug key
  (same behavior as this project's CI for keystore-less test builds). Signer certificate SHA-256:
  `4452371cc1cb11f64739c10c5e04999dee03aca035d3ee4468599b79ac236f3a` — identical to the debug APK,
  so both installs are interchangeable on the phone. No production signing key was created
  (per task rules); a production release still requires the proper keystore.

## Release APK variant with in-app updater (third build)
- **Command:** `.\gradlew.bat assembleRelease` (no property override — default upstream config)
- **Result:** BUILD SUCCESSFUL in 26s (7 executed, 35 up-to-date; only BuildConfig-dependent tasks re-ran)
- **Copy:** `dist\Wispr-release-updater.apk` — size 379,261 bytes
- **SHA-256:** `c65b14293348b65d1df25a866f46a263aba23b78a40db7ab8e4becbe68fa534c`
- **Difference vs `Wispr-release.apk`:** baked-in `SELF_UPDATE=true` with `wispr.repo=DeepuExplainsAi/ALWAYSWISPR`,
  so this build checks upstream GitHub Releases for updates (debug-signed safe: the app detects the
  key mismatch and redirects to installing from where it was built, per upstream UX).
- Same debug-key signing as the other two local builds.

## Android device install
- `adb devices -l` at build time: **no device connected.** Install/transfer steps below.

## License / branding notes
- Original Apache-2.0 `LICENSE` and `NOTICE` untouched (also bundled in-app as assets).
- This is a **personal local debug build of the upstream app** for testing, not a redistributed
  fork. If it is ever redistributed as a fork, Apache-2.0 §6 requires new app name/icon
  (upstream trademark is not granted) — preserve `LICENSE`/`NOTICE`.

## Known limitations
- Debug-signed APK: it will conflict (signature mismatch) with any store-installed or release-signed
  Wispr on the same phone; uninstall the previous copy first (deletes its local data, incl. Groq key).

---

# Audio Modules — Implementation Record (post-build verification)

## Module responsibilities (kept strictly separate)
| Module | Role | क्या नहीं करता |
| --- | --- | --- |
| [Decode.kt](app/src/main/java/com/deepugupta/wispr/Decode.kt) | **Mode B only**: local media import — SAF Uri → MediaExtractor → MediaCodec → mono downmix → Chunker | live capture नहीं |
| [PlaybackCapture.kt](app/src/main/java/com/deepugupta/wispr/PlaybackCapture.kt) | **Mode A only**: live internal playback (AudioPlaybackCapture API, Android 10+), USAGE_MEDIA/GAME/UNKNOWN filter | फ़ाइल decode नहीं |
| [Chunker.kt](app/src/main/java/com/deepugupta/wispr/Chunker.kt) | Shared 16 kHz mono streaming chunker (both modes) + WAV writer | capture या decode नहीं |
| [TranscriptionManager.kt](app/src/main/java/com/deepugupta/wispr/TranscriptionManager.kt) | Chunk → Groq Whisper pipeline, ordered merge, retry, encrypted sessions | audio decode/capture नहीं |

Pipeline (Mode B): `Local MP3/WAV/MP4 → MediaExtractor → MediaCodec → 16 kHz mono PCM → Chunker → Whisper`.
Live internal audio stays on its own engine; neither module can be confused with the other.

## Engineering checks performed (before/while building)
1. **Decode + resample**: `MediaCodec` output is downmixed to mono (`toShorts`, any channel count)
   and `Chunker.feed` resamples to 16 kHz with a position accumulator (`fPos`/`nextOut`) — no drift,
   supports whatever the phone natively decodes (MP3, WAV, M4A/AAC, OGG/Opus, FLAC, MP4/webm/MKV soundtrack).
2. **Chunking losslessness**: every decoded frame goes into exactly one chunk (sparse boundary
   handling only relocates frames to the next chunk); `AudioChunk.startSec` keeps global order —
   no loss, duplication or reordering by construction.
3. **WAV headers**: 44-byte placeholder patched at `seal()` — RIFF size = 36 + data, PCM/mono/16 kHz,
   byteRate `SR*2`, blockAlign 2, dataLen = `frames*2`. Independent per chunk ✓ (verified in code;
   see honest limitation below). Format verified as valid by `spill`-written sizes.
4. **Silence snap is non-aggressive**: `snapQuiet` only selects the quietest 0.25 s window in the last
   ~3 s as the *cut position*; it never drops audio. `snapQuiet=false` cuts straight at fixed length.
   RAM is bounded even for continuous loud music (~6.9 s look-ahead cap).
5. **Memory**: streaming buffer ([SQueue]) — one grow-by-doubling array + one arraycopy per drain,
   **no O(n²) shifting**; completed WAVs go to disk, ~13.8 MB per 9-minute chunk (under Groq's 25 MB cap).
6. **Failure paths**: unsupported/undecodable codec → clear `DecErr`; media/audio missing → `DecErr`;
   cancellation (`isCancelled` in the decode loop + `Chunker.cancelled`) → partial chunks deleted,
   never a fake-complete result; codec/extractor released in `finally`.

## Actual build result (this verification round)
- The build **did fail** on the first real run: `PlaybackCapture.kt:88:18 Unresolved
  'setPlaybackCaptureConfig'` (+8 follow-on type-inference errors from the unresolved builder chain).
- Root cause: the code called `AudioRecord.Builder.setPlaybackCaptureConfig` — checked directly
  against the installed android-36 platform jar with `javap`: the stub method is named
  **`setAudioPlaybackCaptureConfig`** (neither the original short name nor the long
  `…Configuration` public-docs name matches this SDK stub). Fixed to the stub name.
- After fix — `:app:compileDebugKotlin`: **BUILD SUCCESSFUL**; full
  `:app:assembleDebug -Pwispr.selfUpdate=false`: **BUILD SUCCESSFUL** (36 tasks).
- New debug APK copied to `dist/Wispr-debug.apk`: 2,431,567 bytes (~2.4 MB — ~160 KB larger than the
  pre-module build, the cost of the 4 new Kotlin files). Fresh SHA-256s in `dist/SHA256SUMS.txt`.

## Honest test status (what has NOT been proven yet)
- ❗ Real MP3/WAV/MP4 media files have **not yet** been processed by the app end-to-end; per the
  task rule, successful *decoding* is NOT claimed yet — only successful compilation of the modules.
- ❗ No Android device was connected (`adb` returned no device at earlier build time), so
  Mode A on-device capture is also unverified until a phone test.
- The true milestone remains: a playing local MP3 *and* another app's internal audio, both captured
  on the phone and converted correctly into transcripts.

## Remaining implementation order (from the agreed plan)
1. ~~Decode.kt~~ ✓ (compiled)  2. ~~Chunker.kt~~ ✓ (compiled)  3. ~~PlaybackCapture.kt~~ ✓ (compiled, after fix)
4. **CaptureService.kt** — not yet written (next module)
5. TranscriptionManager.kt — written ✓ (compiled)
6. Panel JS Bridge — integration pending (Dependent on CaptureService)  7. TranscriptWorkspace UI — pending

*.kotlin/errors/*.log: temporary Kotlin incremental-cache close-failure logs (compiler daemon RMI);
non-source, non-blocking — the final build ran clean.*

---

# Phase 2 — Live Internal-Audio Capture (PlaybackCaptureService)

## What was implemented (new files)
| File | Responsibility |
| --- | --- |
| [PlaybackCaptureService.kt](app/src/main/java/com/deepugupta/wispr/PlaybackCaptureService.kt) | Foreground service (type `mediaProjection`) owning the full capture lifecycle: IDLE → REQUESTING_PERMISSION → STARTING → RECORDING → STOPPING → COMPLETED/FAILED, exposed via `stateJson()` + `msg` for UI. Owns the MediaProjection (callback registered **before** recording; system-UI stop / revocation ends the session cleanly), the [PlaybackCapture](app/src/main/java/com/deepugupta/wispr/PlaybackCapture.kt) engine, the per-second notification timer and honest zero-audio reporting. |
| [CaptureConsentActivity.kt](app/src/main/java/com/deepugupta/wispr/CaptureConsentActivity.kt) | The only place `createScreenCaptureIntent()` is launched from (real user consent — never from a background service). RESULT_OK → starts the service with the fresh result; decline/cancel → honest `denied` state. Each session consumes a fresh consent; old tokens are never replayed. |

## Edits to existing modules
- [PlaybackCapture.kt](app/src/main/java/com/deepugupta/wispr/PlaybackCapture.kt): projection ownership moved to the service (engine only reads the projection to build the capture config); projection-dead-code (`BuildCompat` junk) removed; AudioRecord lifecycle unchanged.
- [AndroidManifest.xml](app/src/main/AndroidManifest.xml): `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission + `PlaybackCaptureService` (`foregroundServiceType="mediaProjection"`) + transparent `CaptureConsentActivity` (exported=false, excludeFromRecents).
- [Notif.kt](app/src/main/java/com/deepugupta/wispr/Notif.kt): ongoing "Wispr is capturing audio" notification with live duration (`m:ss`) and a working **Stop** action (ID_CAP=46).
- [TranscriptionManager.kt](app/src/main/java/com/deepugupta/wispr/TranscriptionManager.kt): added `captureFinished(...)` — audio captured → "Transcribing…"; **zero audio → session honestly failed** with the policy message ("This audio source does not permit internal playback capture…"), never a fake success.
- [Panel.kt](app/src/main/java/com/deepugupta/wispr/Panel.kt) + [panel.html](app/src/main/assets/web/panel.html): floating assistant now offers **Capture Audio / Stop Capture / Transcript history** and live state (duration ticker, warning text). Mic **Dictate untouched**.
- [BubbleService.kt](app/src/main/java/com/deepugupta/wispr/BubbleService.kt): while capture runs the orb stays visible over ANY app **even with the keyboard hidden** (state light = spinning-arc WORK mode, reset on completion).
- Android 14+ sequence implemented: `startForeground(type=mediaProjection)` runs **before** `getMediaProjection`; no VirtualDisplay (audio-only); MediaProjection callback registered before recording.

## Build (actual results)
- `:app:compileDebugKotlin -Pwispr.selfUpdate=false` → **BUILD SUCCESSFUL (25s)**, zero errors, on first run.
- `:app:assembleDebug -Pwispr.selfUpdate=false` → **BUILD SUCCESSFUL (9s)**, 36 tasks.
- APK verified with `aapt2 dump xmltree`: `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission present,
  `PlaybackCaptureService` with `foregroundServiceType=0x20` (mediaProjection), `CaptureConsentActivity` present.
- [dist/Wispr-debug.apk](dist/Wispr-debug.apk): **2,438,757 bytes**, SHA-256 `a9a9645492ebca8d0281e45fda10750207866f29003b0b77efa320c8a7cce39b` (`dist/SHA256SUMS.txt` refreshed; release APKs untouched this phase).

## Honest test status (nothing on-device verified yet)
- ❗ No Android device was connected at build time — **no live capture session has run on a real phone**.
  Nothing is claimed as "capture works" until tests A–H pass on-device (consent grant/deny, system-UI
  stop, zero-audio sources, AudioRecord cleanup, one full session, PCM sanity).
- ❗ Transcript sessions are stored ordered + encrypted on device; the full TranscriptWorkspace UI
  (view opened from "Transcript history") is the NEXT planned module — the action opens the app.
- Eventual caveat honored by design: apps that mark playback non-capturable stay silent — surfaced
  as a warning, never bypassed.

## Next implementation order (unchanged, remaining)
5. ~~TranscriptionManager~~ ✓  6. ~~Panel JS Bridge (capture actions)~~ ✓ (workspace UI pending)  7. **TranscriptWorkspace UI** — next module

*Device-test milestone: playing local MP3 **and** another app's internal audio both transcribed correctly on the phone.*

## Low-amplitude threshold fix (1 Nov 2026, 03:33)
- **Problem reported:** केवल `SILENCE_RMS = 120` कीेत और नीचे का जूल साल धीमा गाना / धीमी speech का स्टिल फ़ेल SESSION बना देता था, भले ही AudioRecord ने वास्तविक PCM पकड़ लिया हो.
- **Fix (code-verified):** [PlaybackCapture.kt](app/src/main/java/com/deepugupta/wispr/PlaybackCapture.kt) को 4-राज्य निर्णय कर दिया गया:
  `NONE`(no PCM) / `DIGITAL_SILENCE`(PCM लेकिन सब जीरो, `DIGITAL_SILENCE_RMS=2`) / `LOW_LEVEL`(रेल, हकीकी PCM, RMS 3–119) / `AUDIBLE`(RMS ≥ 120).
  - केवल NONE + DIGITAL_SILENCE ही failure है, LOW_LEVEL भी success रूप transcription पहुँचता है;
  - हर frame चाहे किसी भी level का, Chunker में बिना काट-छाँट जाता है — RMS अब केवल डायग्नॉस्टिक है और बाद के ज़्यादा reject नहीं करते;
  - 'डैम मे अब warning केवल तब जब सृजित इन जंगल लो-लेवल AUDIO निकला हो;
- **Edits:** [PlaybackCapture.kt](app/src/main/java/com/deepugupta/wispr/PlaybackCapture.kt), [PlaybackCaptureService.kt:240](app/src/main/java/com/deepugupta/wispr/PlaybackCaptureService.kt#L240), [TranscriptionManager.kt](app/src/main/java/com/deepugupta/wispr/TranscriptionManager.kt) (`captureFinished` अब `note=`/`failMsg=` भी स्वीकार करता है).
- **Build:** `:app:assembleDebug -Pwispr.selfUpdate=false` → **BUILD SUCCESSFUL in 21 s** (exit 0, 36 tasks, 4 executed).
## Floating Bubble keyboard-independence fix (1 Nov 2026, 04:13)
- **Bug reported from the phone:** the floating bubble only appeared when a keyboard was open — over YouTube / Facebook / a music player with the keyboard closed it was gone.
- **Root causes found (audited, not guessed):**
  1. [BubbleService.kt evaluate()](app/src/main/java/com/deepugupta/wispr/BubbleService.kt): the visibility gate `if (!store.bool("bubble") || ime == null) { hide() }` hid the orb whenever no IME window existed — the exact bug.
  2. `imeTop` was never reset when the keyboard closed, so the orb would have repositioned against a dead keyboard edge.
- **Fix (explicit states, nothing blanket-removed):** capture-active state light → dictation-active states (REC/WORK/RETRY/PASTE) → panel-open hides the orb → **Floating Assistant setting gate** (`store.bool("bubble")`) → keyboard-only dictation refinements (own app / password / numeric focus are only hidden while an IME is actually open). With the assistant enabled the orb shows over ANY app, keyboard or not; `imeTop` is reset to 0 when the IME disappears. Bubble placement, drag/snap, animations and mic dictation are untouched.
- **Also:** the service now runs a first `evaluate()` ~400 ms after `onServiceConnected`, so the orb appears immediately after the user turns the Accessibility Service on.
- **In-app settings:** [index.html](app/src/main/assets/web/index.html) toggle renamed **"Floating Assistant"** with the correct description (visible over every app, keyboard or not; off = gone phone-wide); the permission row now explicitly labels the Accessibility entry "Floating Assistant (Accessibility)". Permission statuses + direct routes to the right Settings pages already existed and are kept.
- **Build:** SUCCESSFUL (7 s, exit 0); new APK `dist/Wispr-debug.apk` — **2,440,702 bytes, SHA-256 `12a317f60a50dff30ff901fe44d997bb681eca7b8f70c69b0f5848adb3e6831d`**.
- **Runtime status: UNVERIFIED** — no device is connected to this session (`adb devices -l` returned nothing) and a Gradle build alone does NOT satisfy the acceptance criterion. Device testing (YouTube / Facebook / local player without any keyboard, tap bubble → Capture Audio) must be redone after this install.

## Separate Capture / Audio-Quality / Transcription statuses (1 Nov 2026, 03:38)
- **Instruction:** कोई भी सफल PCM capture transcription की सफलता अप्रयुक्त नहीं करनी चाहिए; तीन स्टेटस अलग रखो और Capture: SUCCESS + Transcription: EMPTY की धारणा को दर्शाया गया.
- **Record fields (नया):** एक जिले के ही स्ट्रीम पर `capture` (`success`/`failed`), `audioQuality` (`normal`/`low`/`digital_silence`/`none`) — [PlaybackCaptureService.kt finishCapture](app/src/main/java/com/deepugupta/wispr/PlaybackCaptureService.kt) से pass होते हैं और [TranscriptionManager.captureFinished](app/src/main/java/com/deepugupta/wispr/TranscriptionManager.kt) session में संग्रहित करता है.
- **Chunk level:** खाली/whisper-noise प्रतिलिपि को अब हमारे नए अलग `nospeech` status में रखा गया ( पहले वाला `failed` अब प्रमाणित रूप सेस नहीं है) — [TranscriptionManager.transcribeChunk](app/src/main/java/com/deepugupta/wispr/TranscriptionManager.kt).
- **Result rule (`refreshStatus`):** सभी chunks `nospeech` हों तो session `status: ok` + नोट **"Capture: SUCCESS · Transcription: EMPTY / NO SPEECH RECOGNIZED (audio kept)"**; वास्तविक RF फेल्युर नहीं म视听ा परपक.
- **Build:** SUCCESSFUL (6 s, exit 0); नया APK: `dist/Wispr-debug.apk` — **2,440,681 bytes, SHA-256 `911d423257edafedb292d1a04bc092ef9d0b4ec6a87f841140007ca8b798bbde`**.
- On-device अभी भी NOT RUN ही है — फोन नहीं है.

- **New dist APK:** `dist/Wispr-debug.apk` — 2,440,195 bytes, SHA-256 `465a76b9994141e882bda3490552b01e876246313dfdeb80f7b14b8e7c5125f4` (`dist/SHA256SUMS.txt` ingest то इसी से अद्यतित).
- Phone connectivity अर अपन-अपन लीव न देखे जाने से बिल्लक पहले जैसे NOT RUN ही है — quiet-speech और low-volume-music tests हैं T4q और उनके अगले स्टेप में हैं, सभी फोन-देवाल सहमति पर फोन्स के साथ काम होंगे.
- In-app updater is compiled out for this build (`SELF_UPDATE=false`) — updates via reinstall.

## Runtime-validation round (1 Nov 2026 — see full record in [TEST_RESULTS.md](TEST_RESULTS.md))
- `adb devices -l` after daemon start: **zero devices attached** — the phone (`58673eb7`) that showed `device` in the earlier PowerShell window is no longer visible to this host. On-device steps (install, permissions, MP3, YouTube, restricted source, dictation regression) therefore remain **NOT RUN** — no test is claimed as passed.
- Verified device-independently: APK (2,438,757 bytes, SHA-256 `a9a9645492…cce39b`) matches `dist/SHA256SUMS.txt`; `:app:assembleDebug -Pwispr.selfUpdate=false` → BUILD SUCCESSFUL (30 s, all 36 tasks up-to-date, exit 0), confirming the dist APK is exactly this tree's build.
- Static code audit findings (in the APK being shipped): pre-designated guard `anyAudio` + `SILENCE_RMS=120` — silence/zero buffers are never counted as success; zero-audio sessions end in FAILED with an explicit policy message (never COMPLETED); chunk order preserved into `TranscriptionManager.enqueueChunk` by `startSec`; verbose_json language detection propagated; Groq key is header-only and unmapped to logs; projection callback → `finishCapture` releases AudioRecord/projection/notification and calls `stopForeground(STOP_FOREGROUND_REMOVE)`.
- Verdict: **BLOCKED at device gate** — if the phone comes back on USB Debugging (reconnect cable → `adb devices -l` until `58673eb7 device`), the install + matrix-section test run in TEST_RESULTS.md §3 is the next action, and TranscriptWorkspace remains on hold until those tests leave real evidence.
- Groq API key: **not** in the build — the user enters their own key in the app (Android
  Keystore-encrypted). No keys were hardcoded, committed, or logged.
- `local.properties` is machine-specific and gitignored — do not commit it.
- Nothing was pushed to GitHub; existing repo ZIP files (`Wispr-by-Deepu-Gupta-v4.1.zip`, etc.)
  were left untouched.
