# DRON.exe — sign-in methods (official vs WebView)

## OpenAI / ChatGPT / Codex
- **Official API/OAuth:** OpenAI does **not** publish a public OAuth or device-authorization flow that
  lets a third-party app obtain ChatGPT/Codex session credentials. The supported programmatic path is an
  **API key** (entered in the Models tab, stored encrypted). So there is **no token-based ChatGPT login** here.
- **WebView:** the "ChatGPT" button opens **https://chatgpt.com** in an in-app WebView. You log in on the
  official site; the session lives in the WebView's own cookie store. We never read or export those cookies/tokens.
  This is an in-app web chat, not an API integration.

## Grok (grok.com / x.ai)
- **Official API:** xAI exposes an **OpenAI-compatible API** at `https://api.x.ai/v1` — use it with an API key
  (set Base URL + key in the Models tab). This is the official programmatic method.
- **WebView:** the "Grok" button opens **https://grok.com** for an in-app web chat; no token scraping.

## Google / Gemini
- **Official API:** Gemini has an **API key** method via `https://generativelanguage.googleapis.com` (OpenAI-compatible
  endpoint `/v1beta/openai`). Enter the key in the Models tab — this is official and needs no secrets in the repo.
- **Google OAuth:** a proper OAuth client requires registering an OAuth client ID per app/package. We do **not**
  ship any client ID/secret in the repo, so OAuth is **not** enabled by default.
- **WebView:** the "Gemini" button opens **https://gemini.google.com** for an in-app web chat; no token scraping.

**Summary:** API-key integrations (OpenRouter default, xAI, Gemini, OpenAI) are the official, programmatic paths.
The ChatGPT/Grok/Gemini buttons are **WebView web-chat** conveniences that use each vendor's official site and
never scrape credentials.
