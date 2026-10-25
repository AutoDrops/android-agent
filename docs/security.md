# Security — DRON.exe v3.22

## Secrets
- OpenRouter API key: entered in-app only → `EncryptedSharedPreferences` (AES-256-GCM, master key in Android Keystore). Never in git, assets, BuildConfig (`EMBEDDED_API_KEY` is always `""`), logs, or release notes.
- ChatGPT OAuth tokens (access/refresh/id): same EncryptedSharedPreferences store (`chatgpt_auth`). Never logged; `OAuthLog` sanitizes JWT/`access_token`/`code`/`code_verifier`.
- Client id for ChatGPT (`app_EMoamEEZ73f0CkXaXp7hrann`) is a **public** Codex CLI id (no client secret). PKCE required.
- Export/backup JSON never includes the API key.

## Network / pentest
- All LAN tools go through `NetGuard`: only RFC1918 (`10/8`, `172.16/12`, `192.168/16`) + own subnet. Public IPs/domains that resolve public → Ukrainian refuse message before any socket.
- No MITM, no credential theft, no exploit payloads, no third-party targeting.

## Dangerous actions
- Risk classes LOW / SENSITIVE / EFFECT / HIGH / ELEVATED. Non-LOW require in-app confirmation (preview of command). Shell/root always confirmed unless session auto-approve (SENSITIVE only).
- Unknown tools and invalid JSON fail closed.
- Tool output treated as untrusted data when fed back to the model.

## Logging
- No Authorization headers or token values in logcat from our code.
- Friendly HTTP errors surface status code without echoing the key.
