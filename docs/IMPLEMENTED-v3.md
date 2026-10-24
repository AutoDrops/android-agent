# DRON.exe v3 — implemented feature ideas (from GPT via OpenRouter)

Full idea list: docs/feature-ideas-v3.md. Implemented in v3:

- **#7 Network Security Posture Report** — `security_report` tool: LAN scan + classification of open risky ports into a structured report.
- **#9 APK Inspector & Permission Risk Explainer** — `app_permissions` tool: lists an installed app's permissions and flags dangerous ones.
- **#12 Privacy Firewall for tool calls** — tool output is scrubbed for secrets (API keys, tokens, bearer, PEM, high-entropy) before being sent back to the LLM; egress host shown in confirmations.
- **#11 Action Recipes / Playbooks** — save a prompt as a reusable one-tap recipe (Room), replay from the Models/Tools area.
- **#1 (partial) App launcher** — existing list_apps/launch_app retained.

Deferred (documented, not shipped): on-device RAG (#2), MediaProjection+OCR (#3), offline voice (#4), event-triggered WorkManager rules (#5), JGit workspace (#10) — larger efforts requiring extra native deps.
