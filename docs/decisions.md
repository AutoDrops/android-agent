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
