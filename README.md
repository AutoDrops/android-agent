# AutoDrops Android Agent

On-device Android AI agent (Kotlin + Jetpack Compose) that chats with any OpenAI-compatible
LLM (OpenRouter default, xAI, OpenAI…) and controls the phone and local network through
function-calling tools, in an agentic loop (write files, run commands, read output, iterate).

## Features
- **Chat** with streaming (SSE, incl. tool-call deltas) and a tabbed dark UI: Chat, History, Tools, Models, Remote.
- **Model picker** from `/api/v1/models` with search, favorites (persisted) and per-model context/price/tool badges.
- **History** persisted with Room.
- **Agentic loop**: the model calls tools, sees output cards in chat, and keeps going up to N steps.
- **Confirmation policy** by risk class (LOW/SENSITIVE/EFFECT/HIGH/ELEVATED), with an optional
  *auto-approve non-elevated for this session* toggle. Shell/root always confirmed.
- **Tools**: network_info, lan_scan (local subnet only), ping, dns_lookup, discover_devices (mDNS/NSD + SSDP),
  dlna_control (AVTransport/RenderingControl), adbtv (ADB-over-network D-pad/keys/launch for Android TV),
  toggle_settings (wifi/bt panels, brightness, volume, DND, flashlight), file_op (workspace sandbox),
  shell (sandbox bash or root), run_script (Python/Node via Termux RUN_COMMAND), list_apps, launch_app,
  notifications (listener), clipboard, contacts, send_sms (ACTION_SENDTO), ui_action (Accessibility).
- **Remote** tab: discover, D-pad, volume, media launch shortcuts.

## Security
- API key entered in-app, stored in **EncryptedSharedPreferences** (AES-256-GCM, master key in the
  Android Keystore). Never in code, logs, or the repo. Refuses to send the key over non-HTTPS.
- Tool output is treated as **untrusted** input; control characters are stripped from confirmation previews.
- Unknown tools and invalid arguments **fail closed**.

## Scripting backend
Python/Node run through the **Termux `RUN_COMMAND` intent** (requires Termux + `allow-external-apps=true`);
in-app bash runs in the app sandbox via `sh -c`. Root/Shizuku optional. This keeps the app Play-safe and
avoids bundling a proot rootfs.

## Build
```
./gradlew assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
```
Requires JDK 17 and the Android SDK (platform 34, build-tools 34).

*MVP — not all tools tested on a physical device. Direct Wi-Fi/Bluetooth toggles open system panels on Android 10+.*

## Build-time OpenRouter key (v3.1)
The debug APK is built locally with an embedded first-run OpenRouter key so the app works on first launch.
The key is read at build time from the `OPENROUTER_API_KEY` environment variable, or from
`openrouter.api.key` in the **gitignored** `local.properties`, and injected as `BuildConfig.EMBEDDED_API_KEY`.
It is **never committed** (`local.properties` and `*.apk` are gitignored) and never printed.
`SecureStore` prefills `EncryptedSharedPreferences` from it on first launch only; the Models tab lets you
replace or **clear** the key at any time (clearing disables re-seeding).
For release builds from CI, the same key would need to be provided as a CI secret/env var — it is **not** in the repo.

## v3.2 features
- **Local RAG** (on-device embeddings, Room-persisted): tools `rag_add`/`rag_search`/`rag_list`/`rag_delete` + UI in Tools tab.
- **Screen OCR**: user-granted MediaProjection capture → ML Kit OCR → `screen_ocr` tool (always consent, one frame, never background).
- **Voice**: push-to-talk STT (Android SpeechRecognizer, uk-UA) in Chat. No custom wake-word (would need a bundled Vosk model).
- **Proactive rules** (WorkManager, ~15-min period): charging / Wi-Fi SSID / interval triggers. Background jobs only read state & notify; agent actions are never run in the background without in-app confirmation.
- **Local SLM**: downloadable-model path is stubbed to keep the APK small (~19MB); OpenRouter is the working fallback.
