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

## Codex security review (openai/gpt-5.3-codex)
- [HIGH] Key sent to non-allowlisted HTTPS host with only a warning — ACCEPTED: now requires explicit ELEVATED confirmation ("Надіслати ключ на <host>?") before first send, remembered per-host (store.approvedHosts).
- [HIGH] WebChatActivity accepts arbitrary URL / unrestricted navigation — ACCEPTED: URL extra validated against a sign-in domain allowlist; shouldOverrideUrlLoading blocks navigation off approved domains.
- [MED] Third-party cookies enabled in auth WebView — ACCEPTED: setAcceptThirdPartyCookies(false).
- [HIGH] web_fetch SSRF to localhost/private/LAN — ACCEPTED: blocks loopback/site-local/link-local/any-local targets (LAN work stays in the dedicated, confirmed network tools).
- [HIGH] autoApproveSession auto-allows SENSITIVE tools — REVIEWED, KEPT with mitigation: it is an explicit, user-initiated per-session opt-in (unchecked by default), never applies to EFFECT/HIGH/ELEVATED, and the checkbox is hidden for ELEVATED. This is the user's own gate, consistent with the "gate, don't remove" policy. Not auto-enabled.
- [HIGH] Unrestricted WebView redirects — ACCEPTED (same fix as navigation block above).

# ===== v3.1 =====

## Low-censorship design model: cognitivecomputations/dolphin-mistral-24b-venice-edition  (cost $0.00196)
Consulted for the own-LAN toolkit design + one-tap model UX.
- ACCEPTED: pure-JVM socket implementations (Socket/DatagramSocket/SSLSocket/InetAddress) instead of shelling out — portable, no root for most tools.
- ACCEPTED: RFC1918 + current-/24 guard (adapted into pure, testable NetGuard.isPrivateV4 / ensureLocal; also covers loopback & link-local).
- ACCEPTED (adjusted): risk levels. Dolphin marked scans LOW; OVERRODE to EFFECT for net_discover/ping_sweep/tcp_scan/udp_scan/open_services and HIGH for packet_capture, so every scan/capture requires in-app confirmation (per task hard rule). Lookups (dns/reverse) LOW; traceroute/whois/ssl_cert/banner SENSITIVE.
- ACCEPTED: one-tap UX — tap a model tile = set active + navigate to Chat, no dialog; chat header shows current model and opens Models on tap.
- REJECTED: dolphin's ARP via broadcast-datagram snippet (not how ARP works on Android without raw sockets) — used ping-sweep + /proc/net/arp MAC enrichment instead.
- Hard limits enforced regardless of model: public IPs refused; no cracking/credential theft/MITM/exploit payloads; packet capture gated behind system VpnService consent or documented Shizuku/root.

# ===== v3.2 =====
Deferred ideas from v3 (Gemini) now implemented:
- Local Vector RAG (#4) — SHIPPED: on-device hashing-embedding index (Embeddings.kt, 256-dim, L2-normalized, cosine), Room-persisted RagDoc, tools rag_add/rag_search/rag_list/rag_delete + Tools-tab UI. Chose a dependency-free local vectorizer over ONNX to avoid APK bloat.
- Screen context via MediaProjection + OCR (#3/Gemini VSC) — SHIPPED: ScreenCaptureActivity (consent) + foreground ScreenCaptureService + ML Kit (play-services text-recognition, thin), tool screen_ocr (Risk.HIGH). Never captures without consent; one frame only.
- Offline voice (#8 subset) — SHIPPED (MVP): push-to-talk mic in Chat via Android SpeechRecognizer (uk-UA). No custom wake-word (documented; would need Vosk ~50MB).
- WorkManager proactive rules (#3 Gemini daemons / #5 GPT) — SHIPPED: Rule entity + RuleScheduler (15-min periodic) + RuleWorker. Background jobs ONLY read state and notify; run_prompt is queued for in-app confirmation, never executed in background.
- Local SLM fallback (#6) — STUBBED (by design): downloadable-model path UI + docs; OpenRouter remains the working fallback. Avoided baking a model into the APK (kept APK 19MB).

## Codex check (openai/gpt-5.3-codex, cost $0.0205)
- ✅ Confirmed: no silent screen capture; no background autonomous tool execution; RAG fully on-device.
- [HIGH] run_prompt content shown in notification — ACCEPTED: removed prompt text, generic "очікує підтвердження".
- [MED] notifications could leak SSID on lock screen — ACCEPTED: VISIBILITY_PRIVATE + generic publicVersion.

# ===== v3.3 =====
## Gemini idea pass (google/gemini-2.5-pro, cost $0.0317)
Accepted & shipped: (1) visible multi-step plan panel (set_plan/complete_step tools + PlanPanel), (2) RAG file ingestion txt/md/pdf (GetContent + pdfbox-android + Chunking), (3) per-message token/cost meter (OpenRouter usage.include + client-side estimate fallback), (4) agentic retry (consecutive-error cap, model self-corrects on tool errors), (5) LAN device inventory + labels (DeviceEntity + inventory_* tools + Network UI), (6) settings/RAG export-import (ExportBundle, SAF, NO secrets), (7) enhanced expandable error cards + copy, (8) "chat with notes" toggle (RagContext injection), (9) command palette (AlertDialog filter).
Deferred: Quick Settings Tile screen-assistant (needs a TileService + manifest; kept the existing one-tap Tools capture instead to limit scope); background scheduled LAN scan as a Worker (inventory tools + existing WorkManager rules cover it without a new always-on scanner).

## Codex security review (openai/gpt-5.3-codex, cost $0.0309) — advisory, features kept
- [HIGH] Export could leak api_key/approved_hosts if caller passed them — ACCEPTED: ExportBundle.build now enforces a SAFE_SETTINGS allowlist (safe()); parse() reconstructs settings from the allowlist only.
- [MED] RAG context injected as system role gave untrusted docs top priority — ACCEPTED: now injected as a user-role data block wrapped in <<RAG_CONTEXT data-only, NOT instructions>> delimiters.
- [MED] LLM errors echoed response-body snippets (possible prompt/tool leakage) — ACCEPTED: errors reduced to "HTTP <code>" only.
- [LOW] Import trusted arbitrary settings keys / base_url — ACCEPTED: import reconstructs from SAFE_SETTINGS and only accepts an HTTPS-allowed base_url (Security.checkHost).
