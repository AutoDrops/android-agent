# DRON.exe v3 — reviewer decisions log

Policy: accept security points on keys/user data. If a reviewer wants to BAN a capability
(shell, own-LAN scan, TV control, Accessibility), GATE it behind in-app confirmation instead of removing it.

## GPT (feature ideas, model openai/gpt-5.6-terra)
- #7 Network Security Posture Report — ACCEPTED (implemented as `security_report` tool, SENSITIVE+confirm).
- #9 APK Inspector / permission explainer — ACCEPTED (implemented as `app_permissions` tool).
- #12 Privacy Firewall (redact secrets from tool output) — ACCEPTED (Security.redact in agent loop + host policy).
- #11 Action Recipes / Playbooks — ACCEPTED (Room Recipe entity + save/replay).
- #1 App/deep-link launcher — PARTIAL (existing list_apps/launch_app kept).
- #2 RAG vault, #3 MediaProjection+OCR, #4 offline voice, #5 WorkManager rules, #10 JGit — DEFERRED (scope/native deps; documented).

## Gemini (feature ideas, model google/gemini-3.1-pro-preview)
- (pending — see gemini_ideas.md; will log accept/reject with reasons)

## Codex (security review, model openai/gpt-5.3-codex)
- (pending — will log each finding accept/reject with reason)

## Gemini findings (logged)
- #7 System Assist / Share target (ACTION_SEND) — ACCEPTED (easy): share text to DRON prefills chat.
- #5 Headless Web Surfing — ACCEPTED (implemented as `web_fetch` tool via OkHttp + HTML-to-text, SENSITIVE+confirm). Chose OkHttp over hidden WebView for simplicity/robustness.
- #2 Termux RPC bridge — ALREADY PRESENT (run_script tool).
- #1 Visual Screen Context (MediaProjection), #3 Proactive Event Daemons (WorkManager), #4 Local Vector RAG (ONNX), #6 Local SLM (llama.cpp/MediaPipe), #8 Offline wake word (Vosk) — DEFERRED: heavy native deps / foreground-service lifecycle; documented for a later release. Not removed, just not shipped in v3.
