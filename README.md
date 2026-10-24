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
