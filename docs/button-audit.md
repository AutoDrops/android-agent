# Button / tab / toggle audit — DRON.exe v3.22

Status legend: **OK** works · **GATE** gated behind permission/confirm/key · **INFO** shows clear Ukrainian status (not a no-op).

## Bottom tabs (Ukrainian labels)
| Control | Expected | Status |
|---|---|---|
| Чат | Opens ChatTab | OK |
| Моделі | Opens ModelsTab | OK |
| Інстр. | Opens ToolsTab | OK |
| Dev | Opens DevTab (files/terminal) | OK |
| Мережа | Opens NetworkTab | OK |
| Налашт. | Opens SettingsTab | OK |

## Chat
| Control | Expected | Status |
|---|---|---|
| DRON.exe / model ▾ | Jump to Models | OK (slug ellipsized) |
| Key banner | Jump to Models when no key (hidden if ChatGPT signed in) | OK |
| RAG toggle | Toggle ragEnabled | OK |
| Mic | Vosk or system STT; offer download dialog | OK / GATE |
| Offline bolt | askLocal when local model ready | OK / GATE |
| Send | Agent loop (OpenRouter or ChatGPT) | OK |
| Stop | Cancel runJob | OK |
| Confirm Allow/Deny | resolvePending | OK |
| Tool card | Shows name, args, exit=N · Xms, stdout/stderr | OK (v3.22) |

## Models
| Control | Expected | Status |
|---|---|---|
| Base URL / API key / Зберегти / Очистити / Перевірити | Keystore store + live ping | OK |
| Оновити | Live catalog | GATE (needs key) |
| Category chips / search / one-tap model | Switch + go to chat | OK |
| Увійти через ChatGPT | Device-code OAuth (Codex CLI) | OK (live login needs Bohdan's account) |
| Вхід через браузер | PKCE + 127.0.0.1:1455 loopback | OK (code-only; needs account) |
| ChatGPT model chips | selectChatGptModel | OK |
| Оновити токен / Вийти | refresh / clear Keystore | OK |
| OAuth event log | Sanitized stages, no tokens | OK |

## Tools
| Control | Expected | Status |
|---|---|---|
| ChatGPT/Grok/Gemini (сайт) | WebView of official sites (NOT in-app models) | OK — labels say «сайт»; ChatGPT in-app is on Models |
| Код-рев'ю | ReviewContent | OK |
| RAG add/delete/import | Local notes | OK |
| Screen OCR | ScreenCaptureActivity | GATE (MediaProjection) |
| Export/import backup + recipes | SAF pickers | OK (never includes API key) |
| Permission buttons | Open system settings / request runtime | OK |
| Recipes ▶ / ✕ | send prompt / delete | OK |

## Dev (coding env)
| Control | Expected | Status |
|---|---|---|
| File tree tap | Open editor | OK |
| Зберегти / Запуск→чат | writeFile / runTerminal | OK |
| Snippets | save/delete/run | OK (blocks secret-looking cmds) |
| Terminal run/stop | Exec.run + exit badge | OK |

## Network
| Control | Expected | Status |
|---|---|---|
| Discover / ARP / security / Wi-Fi | send tool prompts | OK |
| TCP/services/traceroute/TLS/os_detect | RFC1918-gated input | OK — public IP shows UA error |
| Inventory add/delete | Private IPs only | OK |
| ADB TV D-pad | send adbtv prompts | GATE (needs discovered TV) |

## Settings
| Control | Expected | Status |
|---|---|---|
| Capabilities / Self-test / Onboarding | Sub-screens | OK |
| Boot / stream / speak switches | Reactive (remember state) | OK (fixed v3.22) |
| Shell mode chips | sandbox/root | OK (reactive) |
| Rules add/enable/delete | Room + WorkManager | OK |
| LAN watch | Confirm then schedule | OK |
| Tool profile + per-tool switches | Recompose via toolsVersion | OK (fixed v3.22) |
| Routing | small/large model switch | OK |
| Local model Завантажити / %/MB/speed / Запустити / Видалити | LocalModelManager + OnDeviceLlm | OK |
| Engine self-check | Honest on-device probe | OK |
| Vosk download/select/delete/toggles | Offline voice | OK |
| Новий чат | newConversation | OK |

## Boot / onboarding / recovery
| Control | Expected | Status |
|---|---|---|
| Boot screen tap | Skip to UI | OK (Parrot-style green lines) |
| Onboarding permissions / Пропустити / Готово | Runtime perms | OK |
| Recovery copy/clear/dismiss | CrashReport | OK |

## Dead / no-op
None found after v3.22 fixes (non-reactive EncryptedSharedPreferences toggles now use Compose state).

## Gemini / Grok as OAuth models
- **Gemini**: skipped for public APK — Google's published installed-app client secret would have to be embedded (conflicts with "no secrets in APK"). Browser WebView login remains on Tools tab.
- **Grok**: no public subscription OAuth in open source; key-only via OpenAI-compatible base URL (already supported). Browser WebView remains.
