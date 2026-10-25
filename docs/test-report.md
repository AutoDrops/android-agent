# Test report — DRON.exe v3.22

## Environment
- Build box: Linux, JDK 17, no working Android emulator (KVM advertised but guest kernel never boots; tried `-no-window` / software). **Fallback:** Robolectric + JUnit + Paparazzi (UI) + live HTTP against OpenRouter from the app's real `LlmClient`.
- Physical device: not available to the build agent. Bohdan installs the GitHub Release APK on his phone for smoke.

## Automated
- `./gradlew testDebugUnitTest`: full suite (see CI log). v3.22-specific:
  - `V322Test` 29 — PKCE/RFC7636, authorize URL, token parse/refresh, device-code parse, Responses mapping, SSE, text-tool shapes (JSON/sh/XML/Hermes/GLM), no embedded key.
  - `V322IntentTest` 7 — ForcedIntent for dumpsys/top/free, «не виконуй» respected, Exec exit/stderr/duration format.
  - `V322EngineTest` 7 — ToolCallEngine decide order + LoopbackAuth CSRF/happy path.
- Live (key file `/workspace/free-api/keys/openrouter-dron.key`, never printed/committed; tests skip on HTTP 402/429):
  - `dolphin-mistral-24b-venice-edition` (paid, ~$0): with text protocol emitted `shell {"command":"free -m"}`, ate TOOL_RESULT, answered with **3612 MB free**.
  - `poolside/laguna-s-2.1:free`: emitted GLM `<tool_call>shell…`, same end-to-end pass after XML parser.
- ChatGPT OAuth: **not live-tested** (no ChatGPT account on the box). Unit coverage for PKCE, URL, token parse, refresh errors, device-code, Responses SSE mapping, loopback CSRF. Device-code is the primary phone path; browser PKCE+1455 is the fallback.

## Emulator
KVM guest does not start Android on this box (3 prior attempts; kernel never boots). Documented; does not block OAuth/tool work. Device smoke is on Bohdan's phone after install.

## Manual acceptance remaining on device
1. Paste OpenRouter key → Перевірити → chat answer.
2. ChatGPT: Увійти через ChatGPT → enter code on auth.openai.com/codex/device → pick chatgpt/* model → chat.
3. Prompt `Виконай dumpsys battery` → tool card with exit= / stdout.
4. Local GGUF: Завантажити → %/MB/speed → Запустити → offline reply.
5. Public IP `8.8.8.8` in Network tab → blocked.
