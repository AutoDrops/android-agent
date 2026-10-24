1. **App Intent & Deep-Link Launcher**  
   **What:** Index installed apps’ exported activities, shortcuts, and deep links; let the agent open specific screens such as Wi‑Fi settings, a banking app’s transfer page, or YouTube search.  
   **Why powerful:** Turns natural-language requests into direct app navigation instead of brittle UI tapping.  
   **Feasibility:** **Medium** — use `PackageManager`, launcher shortcuts, intents; exported/non-exported restrictions apply.

2. **Local Semantic Memory / RAG Vault**  
   **What:** Build an on-device searchable memory over notes, files, chats, clipboard history, and optionally selected folders using local embeddings/FTS.  
   **Why powerful:** Lets DRON answer “what did I decide about the router last week?” without sending personal data to a cloud service.  
   **Feasibility:** **Medium** — Room + SQLite FTS5, optional TensorFlow Lite/ONNX embedding model, SAF file access.

3. **Screenshot + UI Understanding Pipeline**  
   **What:** Capture screenshots and combine OCR, Accessibility node trees, and optional vision-model analysis to identify controls and verify completed actions.  
   **Why powerful:** Makes automation robust when labels are missing, layouts change, or apps use custom-rendered UI.  
   **Feasibility:** **Medium/Hard** — `MediaProjection`, ML Kit on-device OCR, AccessibilityService; screenshots require user consent.

4. **Offline Voice Command Mode**  
   **What:** Add push-to-talk/wake-button commands with on-device speech-to-text, text-to-speech replies, and a low-latency “agent action” mode.  
   **Why powerful:** Makes DRON usable hands-free for device control, home-network tasks, and quick automations.  
   **Feasibility:** **Medium** — Android `SpeechRecognizer` may vary offline; bundle Whisper.cpp/Vosk-style local inference or use OpenRouter optionally.

5. **Event-Triggered Automation Rules**  
   **What:** Create rules such as “when connected to home Wi‑Fi, enable ADB TV discovery,” “at 10% battery, disable scanning,” or “when a USB device appears, back up photos.”  
   **Why powerful:** Converts DRON from a reactive chatbot into a persistent automation agent.  
   **Feasibility:** **Medium** — `WorkManager`, `AlarmManager`, `BroadcastReceiver`, network callbacks; Android background limits need careful handling.

6. **Android Notification Triage & Action Agent**  
   **What:** Read allowed notifications, classify/cluster them locally, generate summaries, suggest replies, dismiss noise, and trigger notification actions with confirmation.  
   **Why powerful:** Gives users an inbox-like AI layer across messaging, delivery, calendar, downloads, and system alerts.  
   **Feasibility:** **Medium** — `NotificationListenerService`; replying/actions depend on each notification exposing `PendingIntent`s.

7. **Network Security Posture Report**  
   **What:** Turn existing Wi‑Fi/LAN discovery and port scans into a structured report: unknown devices, open risky ports, weak router settings hints, and change detection.  
   **Why powerful:** Makes DRON a practical pocket network-auditing assistant for the user’s own LAN.  
   **Feasibility:** **Medium** — local discovery/scanning is straightforward; router configuration inspection is vendor-dependent.

8. **Local Device Inventory with Fingerprinting**  
   **What:** Maintain a Room-backed inventory of LAN/Bluetooth/USB devices with MAC/IP history, mDNS/SSDP services, likely vendor/type, and “last seen” status.  
   **Why powerful:** Enables commands like “find the living-room TV,” “tell me what changed on my network,” or “alert me when my NAS disappears.”  
   **Feasibility:** **Medium** — use NSD/mDNS, SSDP, ARP/neighbour data where available, Bluetooth APIs, and periodic scans.

9. **APK Inspector & Permission Risk Explainer**  
   **What:** Analyze APKs or installed-app metadata: permissions, exported components, trackers/domains, signing certificates, SDK versions, and dangerous configuration patterns.  
   **Why powerful:** Lets users ask “is this APK suspicious?” or “which installed apps can read notifications/location?” directly on-device.  
   **Feasibility:** **Medium** — `PackageManager`, APK ZIP/manifest parsing, certificate inspection; deeper decompilation is harder but optional.

10. **Local Git Workspace Agent**  
   **What:** Add full Git support to the dev workspace: initialize/clone local repos, diff review, commit generation, patch application, branch management, and conflict assistance.  
   **Why powerful:** Makes DRON a real mobile coding environment where the agent can safely propose and apply reviewable changes.  
   **Feasibility:** **Medium** — integrate JGit or invoke a bundled Git/Termux-compatible binary; filesystem access via SAF/app workspace.

11. **Reproducible “Action Recipes” / Playbooks**  
   **What:** Save successful multi-step agent runs as editable recipes: tool calls, parameters, required permissions, confirmations, and rollback steps.  
   **Why powerful:** A one-off request like “scan TV, connect ADB, install/update app, verify launch” becomes a reusable one-tap workflow.  
   **Feasibility:** **Easy/Medium** — serialize tool-call plans in Room/JSON and expose them as user-editable workflows.

12. **Privacy Firewall for Agent Tool Calls**  
   **What:** Add a policy engine that redacts secrets, blocks risky shell/network commands, requires confirmation for sensitive actions, and shows exactly what leaves the device for OpenRouter.  
   **Why powerful:** Makes a highly capable phone agent safer and more trustworthy, especially with contacts, clipboard, files, ADB, and network tools.  
   **Feasibility:** **Medium** — implement tool schemas with risk levels, regex/entropy secret detection, allowlists, and per-tool consent gates.