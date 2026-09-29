# WisprCheap for Android

[![Build](https://github.com/Hexalyse/wisprcheap-android/actions/workflows/build.yml/badge.svg)](https://github.com/Hexalyse/wisprcheap-android/actions/workflows/build.yml)

Push-to-talk voice dictation for **any Android app**, paying only for the APIs you use. A small bubble
appears when you type in a text field. You talk, and the text is transcribed, cleaned up by an LLM and
inserted at the cursor.

This is the Android port of the [wisprcheap](https://github.com/Hexalyse/wisprcheap) desktop app. It uses
the same providers, prompts, prices and history format.

## Features

- **Hold the bubble** to talk, release to insert. **Tap it** to record hands-free, with Cancel / Send.
- **Speech-to-text** with ElevenLabs Scribe (default) or OpenAI. Your dictionary is sent as keyterms
  or as a prompt.
- **LLM cleanup**: removes filler words and false starts and fixes punctuation. It works with any
  OpenAI-compatible endpoint (OpenAI, Groq, OpenRouter, Ollama…).
- **Command mode**: slide up while holding (or tap ✨) and speak an instruction ("make it more formal",
  "translate to German"). It is applied to the selected text, or writes new text at the cursor.
- **Translation mode**: language pairs such as French → English, switched from the app or a Quick
  Settings tile.
- **Text insertion** works like a keyboard, without replacing yours and without touching the
  clipboard. There are fallbacks for unusual apps.
- **History, monthly cost and word stats, and a log**, all kept on the phone. The history can be
  exported in the desktop `history.jsonl` format.
- **Optional sync** with your computers and other phones through your own
  [wisprcheap sync server](https://github.com/Hexalyse/wisprcheap/tree/master/server) (see below).
- Haptic feedback, a draggable bubble that follows the keyboard, excluded apps, and a dark/light
  Material You theme.

Typical cost: about **$0.35 per 10,000 dictated words** with the defaults (Scribe v2 + gpt-6-luna).

## Install

Requires **Android 13 or newer**.

1. Download `WisprCheap-x.y.z.apk` from the [latest release](https://github.com/Hexalyse/wisprcheap-android/releases/latest)
   and open it. Allow your browser or file manager to install apps if Android asks.
2. Open **WisprCheap** and follow the **Finish setup** card:
   - allow the microphone and notifications;
   - turn on the **accessibility service**. In the list, open "WisprCheap dictation bubble" (under
     *Downloaded apps*). If the switch is greyed out ("Restricted setting"), go to *App info → ⋮ →
     Allow restricted settings* and try again;
   - add your **ElevenLabs** and/or **OpenAI** API keys (Settings → API keys, with a Test button).
3. Tap a text field in any app, hold the bubble, and talk.

Updates install over the previous version: every release is signed with the same key.

## Gestures

| You do | It does |
|---|---|
| Hold the bubble still (0.2 s), talk, release | Transcribes, cleans up and inserts the text |
| Tap the bubble | Hands-free recording with a Cancel / ✨ Command / Send toolbar |
| Slide up while holding | The recording becomes a command on the selected text |
| Slide away while holding | Cancels |
| Drag the bubble | Moves it (it keeps its distance above the keyboard) |

## Sync (optional)

If you run a [wisprcheap sync server](https://github.com/Hexalyse/wisprcheap/tree/master/server), the
phone can share its settings with the desktop app and your other phones: speech-to-text, cleanup,
command and translation settings, the API keys, the dictionary, translation pairs and custom prices.
The history can be uploaded too, so Home can show **this month for all your devices**.

1. On the server's web page, click **Connect a device**.
2. Scan the QR code with the phone's camera (it opens WisprCheap), or go to *Settings → Sync* and type
   the server address and the 8-character code.
3. The first device chooses a **sync passphrase**; the next ones ask for it.

Everything is end-to-end encrypted with that passphrase before it leaves the phone: the server only
reads the history statistics (dates, durations, models, word counts and costs), never your text or
keys. The bubble, recording, text insertion and history options stay per phone. The phone syncs when
you open the app, a few seconds after you change a setting or dictate, and from *Sync now*; there is
no background schedule.

## Privacy

- The accessibility service only looks at the focused text field: its type, and the text around the
  cursor or the selection when you dictate. It is needed to show the bubble, record from other apps
  and insert text.
- Audio goes only to the speech-to-text service you chose, and text only to the LLM endpoints you
  configured. There are no analytics. With sync on, the encrypted profile and history also go to your
  own sync server.
- API keys (and the sync token and key) are encrypted with a key held in the Android Keystore.
  Settings, history and logs stay in the app's private storage and are excluded from backups.

## Build from source

Requirements: JDK 17+ and the Android SDK (platform 37, build-tools 36). Android Studio provides both.

```sh
./gradlew :core:test :app:assembleDebug    # tests + debug APK (installs as "WisprCheap debug", next to the release)
./gradlew :app:assembleRelease             # release APK (minified)
```

Release builds are signed when a key is configured, either through a `keystore.properties` file at the
repository root (not committed):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

or through the `SIGNING_KEYSTORE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and
`SIGNING_KEY_PASSWORD` environment variables. The GitHub workflow builds and tests every push. For a
`v*` tag matching `wisprcheap.versionName` in `gradle.properties`, it also publishes a signed release.

- The `:core` module holds the platform-neutral logic: API clients, prompts, pricing, history,
  gestures and the processing pipeline, with JVM unit tests.
- The `:app` module holds the Android parts.
- [PLAN.md](PLAN.md) is the full design document.

## License

[MIT](LICENSE)
