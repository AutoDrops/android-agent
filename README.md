# DRON.exe

On-phone AI agent (Kotlin + Jetpack Compose) — Codex-on-Android. Ukrainian UI, Parrot/Kali aesthetic.

**Repo:** [AutoDrops/android-agent](https://github.com/AutoDrops/android-agent)

## What it does
- **OpenRouter** — paste API key in-app (Android Keystore). Any model, category catalog, one-tap switch, streaming, clear HTTP errors. **No embedded keys.**
- **ChatGPT / Codex (subscription)** — Sign in with ChatGPT like the open-source Codex CLI (`auth.openai.com`, public client id, PKCE, device-code preferred; browser loopback on `127.0.0.1:1455` as fallback). Tokens in Keystore. Models appear as `chatgpt/…` and use the Codex Responses backend with the same tool loop.
- **Local GGUF** — download with % / MB / speed, explicit **Запустити**, offline chat via llama.cpp / MediaPipe.
- **Agent tools** — shell, files, scripts, apps, accessibility, SMS (where permitted), own-LAN scans. Tool name + args + `exit=N · Xms` + stdout/stderr shown in chat. Models without native function-calling use a text/XML tool protocol; obvious shell intents are forced if the model only narrates. Dangerous actions need confirmation.
- **Coding env** — workspace file tree, editor, terminal, snippets.
- **LAN** — discover / ARP / ports / traceroute / DNS / SSL / TV(ADB) — **RFC1918 only**.

## Not in the public APK
- OpenRouter keys, ChatGPT tokens, Google client secrets.
- **Gemini as OAuth model** skipped: would require embedding Google's published client secret. Browser login to gemini.google.com remains on the Tools tab.
- **Grok OAuth**: none public; use an xAI key via OpenAI-compatible base URL, or the Grok website button.

## Build
```bash
export JAVA_HOME=~/jdk17   # or any JDK 17
./gradlew assembleDebug    # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```
Never set `openrouter.api.key` / `EMBEDDED_API_KEY` for public releases.

## Security
See [docs/security.md](docs/security.md). Tests/audit: [docs/test-report.md](docs/test-report.md), [docs/button-audit.md](docs/button-audit.md).
