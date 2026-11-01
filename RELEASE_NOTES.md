## Wispr v4.1.4

- Latest changes

## Wispr by Deepu Gupta v4.1.0

**New: in-app updates**
- Wispr now tells you inside the app (and with a notification) when a new version is on GitHub. Tap **Update now**: it downloads, verifies, and installs over the old one. No more uninstalling or visiting GitHub.
- Auto mode downloads on Wi-Fi in the background; one tap installs. Change it in **Settings → Updates** (Auto / Just notify / Off) or tap **Check now**.
- Safe by design: every update must be the same app, signed with the same key, with a matching GitHub checksum.
- Your Groq key, settings and history are kept.

**Upgrading from v4.0.0 or older:** if Android says "App not installed", that build was signed with a different key. Uninstall once and install this APK; every update after this one works from inside the app.

### v4.0.0

**New**
- **Mini window**: hold the bubble for Write / Polish / Translate, target language, Hindi ⇄ Hinglish, AI models, AI chat and Understand screen.
- **AI chat on any screen** with your Groq key. Type or speak, then **Insert** the answer into the box you were in.
- **Understand screen in one tap**: any language on screen, translated and explained. **Scan picture (OCR)** reads text in images, videos and PDFs (Android 11+). Also works from Android's Accessibility button in any app.
- **More free models**: Qwen 3.8 27B and Qwen 3.6 27B (vision), Groq Compound and Compound Mini (web search), plus a live "what's available" check.

**Fixed**
- **Notes / Docs / Word losing old text** when you dictate again after saving. Wispr now only inserts at the cursor and never replaces a field's content.
- **Accuracy with fast speech**: Whisper Large v3 by default, a speech-tuned mic at higher quality, your Spellings words taught to the mic, and a "fix misheard words" pass.
- **Build**: compileSdk/targetSdk 37 (minSdk stays 26), AGP pinned to the released 9.4.0, build-tools 37.0.0.
- Free-plan friendly: waits as long as Groq asks on rate limits, and tells you clearly when a daily limit is used up.

**Licence**: now open source under Apache 2.0. © 2026 Deepu Gupta.
