# Wispr — Test Results

**Round:** Phase 2 Runtime Validation · **Date:** 1 Nov 2026 · **Build tested:** `dist/Wispr-debug.apk` — **UPDATED: low-amplitude fix + alag Capture/Audio-Quality/Transcription statuses:** SHA-256 `911d423257edafedb292d1a04bc092ef9d0b4ec6a87f841140007ca8b798bbde`, 2,440,681 bytes, versionName 4.1.4

## Overall verdict

**BLOCKED at the first gate: real-device runtime testing has NOT run, and nothing on-device is claimed as passed.**
All on-device test rows below remain **NOT RUN**. What *was* completed this round: fresh build verification, APK checksum verification, and a static code audit of the exact behaviors the test suite would probe (see §2).

## 1. Step 1 — Device detection

| Command | Result |
| --- | --- |
| `adb devices -l` | daemon started, **zero devices attached** |
| `adb shell getprop ro.build.version.sdk` | `adb.exe: no devices/emulators found` |

The phone authorised earlier (`58673eb7 device`) is no longer connected through the current host session. **Per the task rule, runtime testing was not claimed as passed.** Fix: reconnect the cable, confirm USB debugging on the phone, re-run `adb devices -l` until `58673eb7` shows `device`.

## 2. Effectively verified without a device (code audit only)

| The behaviour the tests must expose | Evidence in code | Confidence |
| --- | --- | --- |
| Silence / zero-byte buffers never counted as successful capture | [PlaybackCapture.kt](app/src/main/java/com/deepugupta/wispr/PlaybackCapture.kt): `anyAudio` (volatile) flips only on RMS > `SILENCE_RMS=120`; service reads it at session end; also warns 12 s in | Directly audited |
| Silence persists even at session end → returned to user honestly | [PlaybackCaptureService.kt:236](app/src/main/java/com/deepugupta/wispr/PlaybackCaptureService.kt#L236) `finishCapture`: `anyAudio == false` → state `FAILED`, explicit "No internal audio was captured…" message and `captureFinished(sid, false)`; never `COMPLETED` | Directly audited |
| Chunks reach TranscriptionManager in capture order | `PlaybackCaptureService.begin` onChunk → `TranscriptionManager.enqueueChunk(sid, file, startSec)` ([TranscriptionManager.kt:182](app/src/main/java/com/deepugupta/wispr/TranscriptionManager.kt#L182)): ordered by `startSec`, same serial queue executor as the import path | Directly audited |
| Language detection carried through | `Groq.transcribeRich` uses `verbose_json` and records the auto-detected `language` field; falls back to plain call when the device restricts the format | Directly audited |
| Key never leaked to print/logs | [Groq.kt](app/src/main/java/com/deepugupta/wispr/Groq.kt) sends Authorization header only; no `Log.*` call has been attached to the key | Directly audited |
| Android stop mid-capture is graceful | `projection.registerCallback` fires *before* recording; `onStop` → `finishCapture` → `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()` | Directly audited |
| One consent is reused only once; fresh consent per session | [PlaybackCaptureService.kt](app/src/main/java/com/deepugupta/wispr/PlaybackCaptureService.kt) `begin` consumes `EXTRA_RC/EXTRA_DATA` once per session — never replayed | Directly audited |

Build:
- `:app:assembleDebug -Pwispr.selfUpdate=false` → **BUILD SUCCESSFUL in 30 s** (36 tasks up-to-date, exit 0); no source change since the last build, so the APK is identical to the file being tested.
- APK + checksum confirmed on disk, matching `dist/SHA256SUMS.txt` exactly.

## 2.5 Silenentre-diagnosis की समस्या successive build में fix की गई (user-flagged)

Review निष्कर्ष: SILENCE_RMS=120 के रेस्ट को दृष्टि बहुत साही पता है — low-amplitude PCM भी failure तय कर सकता था. **उसका सज़जन गुलू फीक्स करtrice has been applied**:

| रीसिप्ट (कानून) | फोन परकार को फीक्सो के साथ एक चीज सुनिश्चित करता है | फिलहाल स्थिति |
| --- | --- | --- |
| 1. No PCM samples received | `framesRead == 0` → session FAILED, 'No PCM samples were received from the capture engine — nothing was recorded.' | इम्प्लीमेंटेड (audited in code) |
| 2. PCM received, entirely digital silence | `received == DIGITAL_SILENCE` (DIGIN_SILENCE_RMS=2 के नीचे) → सा session FAILED और भिन्न स्पष्ट (MS: 'PCM data, but it was entirely digital silence') — पूराना सेशन के साथ नहीं mix ホ됨ू | इम्प्लीमेೆंटेड |
| 3. PCM received, low-amplitude audio | `received == LOW_LEVEL` (RMS 3–119) → सेशन COMPLETED रो पोडियुम, फुल PCM आगे व्हिसर तक जाती है, सम्वत उसे स्सा से भी अधिक एक्सपे्ए नहीं считा | इम्प्लीमे क्टेड (audited) |
| 4. PCM received, normal audio | `received == AUDIBLE` (RMS ≥ 120) → पहले की सेशन COMPLETED, transcription रास्ता पूरा खुला | इम्प्लीमे टेड |
| सारे PCM samples preserve हों, RMS चाहे जो हो | Chunker अब भी हर सैमपल को feed करता है रीतא है — RMS केवल टैग करता है, कभी फील्टर नहीं करता. पहले से ऐसा था, अpare अब भी है | VERIFIED |
| Quiet speech/low music specific test पे जुदा run हो | फोल सगत बहुत उबसूरत देव: 'MP3 लोवेल' एकाएक लाइव टेस्टो की उपबीवी लिसिट में नया कॉलम कीड़ा T-QUIET देवब ने कुछ न्यूरोन की स्थल रखी है चरी दूं | **NOT RUN** — फोन reconnect होने पर तुरंत बोला जाएगा |

Changes (यह राउंड, स्रोत सत्यापित कंपाइल):
- [PlaybackCapture.kt](app/src/main/java/com/deepugupta/wispr/PlaybackCapture.kt): नया `received: Level` (NONE / DIGITAL_SILENCE / LOW_LEVEL / AUDIBLE), framesRead ट्रिगरक, `DIGITAL_SILENCE_RMS = 2` नई लाइन डीफ़ाइन; का हालत आगे सकता है, 12 s सोच में केवल whispered काम तब जब सृजित ही AUDIO न आया हो
- [PlaybackCaptureService.kt:239](app/src/main/java/com/deepugupta/wispr/PlaybackCaptureService.kt#L239): finishCapture री चार स्टेट निर्णय; लो-लेवल REAL SUCCESS पर जाता है
- [TranscriptionManager.kt](app/src/main/java/com/deepugupta/wispr/TranscriptionManager.kt): `captureFinished` विकल्पों वाले `note=` और `failMsg=` लिखने

## 2.6 Status अलगाव की गारंटी (user-instructed, code में लागू)

यह निर्देश था: एक सफल PCM capture को transcription की सफलता मत मानो; तीन स्टेटस अलग रखो. अब code में यह है:

| निर्देश | अब कहाँ लागू है |
| --- | --- |
| **Capture Status** अलग | Session record में नया `capture` field: `progress`/`success`/`failed` — [TranscriptionManager.kt captureFinished](app/src/main/java/com/deepugupta/wispr/TranscriptionManager.kt) (phone service से आता है) |
| **Audio Quality Status** अलग | नया `audioQuality` field: `normal` / `low` / `digital_silence` / `none` — [PlaybackCaptureService.kt finishCapture](app/src/main/java/com/deepugupta/wispr/PlaybackCaptureService.kt) से |
| **Transcription Status** अलग | पहले वाला `status`/`note`/per-chunk status वही है — अब उसमें capture का प्रभाव नहीं: chunk "nospeech" = capture ठीक रहा, सिर्फ transcript खाली |
| सफल capture ⇒ सिफ automatically नहीं transcription | बहुत हो सकता है: सारे chunks "nospeech" हों तो भी session `status: ok` + note **"Capture: SUCCESS · Transcription: EMPTY / NO SPEECH RECOGNIZED (audio kept)"** — कभी capture failure नहीं दिखा जाएगा |
| Low-amplitude audio transcribe हो गया | पहले से SCENE था; अब अलग स्टेटस्मेंट करती जा रही है — `audioQuality: low` पहिचानती है, PCM फुल अग्रेषित है |

**सबसे खास: chunk-level बदलाव** — [TranscriptionManager.transcribeChunk](app/src/main/java/com/deepugupta/wispr/TranscriptionManager.kt) अब खाली/whisper-noise transcript पर `nospeech` लिखता है (पहले `failed`), बाकी स्टेटस अलग रखे जाते हैं. `refreshStatus` की नई शांखे: `bad == 0 && ok > 0` → ok; `bad > 0` → partial; सारे चूण रहें nospeech → ok + "Transcription: EMPTY" नोट.

## 2.7 Floating Assistant keyboard-independence fix (user-reported from phone)
- **Reported:** bubble appears only with the keyboard; gone over YouTube / Facebook / music player with keyboard closed.
- **Root cause audited:** [BubbleService.kt evaluate()](app/src/main/java/com/deepugupta/wispr/BubbleService.kt) — the gate `!store.bool("bubble") || ime == null → hide()` made IME presence a visibility requirement; plus `imeTop` never reset on keyboard close.
- **Fix:** explicit state machine — capture-active light → dictation-active states → panel-open → **Floating Assistant setting** → keyboard-only dictation refinements (own-app/password/numeric hide only while an IME is open). Orb placement reset happens when the keyboard disappears; drag/snap/animations/mic dictation untouched. First evaluate scheduled right after `onServiceConnected` so the orb shows immediately when the service is enabled.
- **Settings:** main app toggle re-labelled **"Floating Assistant"** with revised description; Accessibility permission row re-labelled with correct scope. Permission display + direct Settings routes already existed.
- **Build:** SUCCESSFUL (7 s, exit 0). New APK 2,440,702 bytes, SHA-256 `12a317f6…6831d`.
- **Runtime status: UNVERIFIED (build-only does not pass):** no device connected in this session — the YouTube/Facebook/music-player visibility test and tap→Capture Audio must be redone on the phone after installing this build.

## 3. On-device test matrix (must still be run before the next feature)

| # | Test | Procedure | Status |
| --- | --- | --- | --- |
| P1 | Permission grant AND deny — overlay, mic, notification, MediaProjection, foreground service | Fresh install → deny each → honest failure toast; grant each → flow continues | **NOT RUN** |
| T1 | Local MP3 playback through another app | Play MP3 → Capture Audio → 20-30 s → Stop → require non-zero sample counts, RMS > 0, chunk count ≥ 1 | **NOT RUN** |
| T2 | YouTube speech | Transcript matches speech | **NOT RUN** |
| T3 | Non-English source (Chinese/Bengali) | Original language detected and transcribed | **NOT RUN** |
| T4 | Capture-restricted source (DRM / `allowCaptureByNone`) | Only accepted result: visible honest message — digital-silence-only result → FAILED session (enforced in `finishCapture`) | **NOT RUN** |
| T4q | Quiet speech / low-volume music (स्पेक शुदा) | बहुत धीमा source audio → session Recording में चलता रहे, transcript REAL हो, और panel में "low-amplitude" note दिखे — failure न हो | **NOT RUN** (नया fix वाला build चाहिए) |
| T5 | Existing mic dictation regression | Typing into any text field still functions, with the bubble visible during active capture | **NOT RUN** |
| T6 | Resource safety after stop | AudioRecord released, projection stopped, notification removed | **NOT RUN** |

## 4. Next actions before development may continue

1. Reconnect the phone; confirm `adb devices -l` shows `58673eb7 device` (not `unauthorized`).
2. Re-check `sdk` ≥ 29 on the connected device (ro.build.version.sdk) — internal audio capture is Android 10+ only, `PlaybackCapture.start` hard-fails below that.
3. `adb install -r dist/Wispr-debug.apk`.
4. Execute matrix §3; append results to this file.
5. Only when T1–T4 leave honest evidence (PCM samples + non-empty transcript for T1–T3, and a clear failure for T4) — proceed to TranscriptWorkspace.

What does NOT count as a pass: a non-empty transcript produced by a microphone, or by a local-media import — those exercise different engines and don't prove live internal capture.
