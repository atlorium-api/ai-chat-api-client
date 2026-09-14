# AI Chat API — LLM in your app: text summarization, chatbot, AI completion

[Русский](README.md) · **English**

[![Live API tests](https://github.com/atlorium-api/ai-chat-api-client/actions/workflows/examples.yml/badge.svg)](https://github.com/atlorium-api/ai-chat-api-client/actions/workflows/examples.yml)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-Swagger-brightgreen)](https://atlorium.com/aichatAPI)

Ready-to-run examples for the **AI chat API** in six languages: **Python, TypeScript (Node.js), Go, Java, C#, PHP.**
Add an **LLM to your application** with a single HTTP request: **text summarization**, text generation, a **chatbot API** with conversation memory. One **AI completion API**, one key, token usage returned with every reply.

Every example **runs out of the box — no signup, no key, no card.** A public demo key is baked in.

```bash
git clone https://github.com/atlorium-api/ai-chat-api-client
cd ai-chat-api-client/python && pip install -r requirements.txt && python main.py
```

```
Демо-ключ: ответ модели — заглушка (мок). Реальная ИИ-модель не вызывается, токены не тратятся.

Доступные модели (GET /api/AiChat/models):
  [ ] local     Приватная — вход до 6000 токенов, недоступна
  [*] basic     Базовая — вход до 4000 токенов, доступна
  [ ] advanced  Продвинутая — вход до 6000 токенов, недоступна
  [ ] best      Лучшая — вход до 8000 токенов, недоступна

Выбрана: basic (Базовая) — первая доступная в списке.
  Лимит входа: 4000 токенов

Проверка длины до отправки:
  Символов в запросе: 766
  Оценка токенов: ~192 (приблизительно: символы / 4)
  Влезает — отправляем.

── Выжимка ───────────────────────────────────────────────────────
Это ответ песочницы (mock): реальная ИИ-модель не вызывалась и токены не тратились. ...
──────────────────────────────────────────────────────────────────

Модель: basic
Токены: prompt 59 + completion 58 = 117
Сессия: dd8e248a7db5772ac46e7bca824e38cc

GET /api/AiChat/session/{id} → 404: в песочнице история сессии не сохраняется.
```

(The examples print in Russian — the service is Russian-first, and the sandbox reply is Russian text. The code and the API itself are language-agnostic: send English, get English.)

> **What the demo key shows — and what it does not.** The sandbox has two honest boundaries here, and both are visible in the output above.
>
> 1. **`reply` is a stub, not the model's work.** The mock says so itself: "the real AI model was not called and no tokens were spent". You **cannot** see actual summarization in the sandbox — only the mechanics: model selection, length checks, token accounting, sessions. A live key returns a real summary with **the same code**, no edits.
> 2. **Sessions are not persisted in the sandbox.** `GET /api/AiChat/session/{id}` returns `404` immediately after `/send` handed you that very `sessionId` — verified. The examples handle this gracefully and explain it instead of crashing. With a live key the session lives for an hour and works.
>
> Everything else is real: routes, error codes, limits, response shape. You can write and test the integration before paying.

---

## What it is for

Summarizing support tickets and email threads, extracting meaning from reviews, drafting support replies, generating product descriptions, classifying inbound requests, chatbots with conversation memory. One POST request — no GPU, no model hosting, no separate contract with a foreign LLM provider.

The examples do not just print JSON — they **apply** the API. Each ships a `summarize()` function that does two things a naive example does not.

**1. It does not hardcode the model.** First `GET /api/AiChat/models`, then pick the first model with `isAvailable: true`. The available set is server configuration and it changes; an example that bakes in a specific `id` will silently break one day. If nothing is available, the example says so and exits instead of firing a doomed request.

**2. It checks length BEFORE sending.** `POST /send` is the billed call. Sending text that cannot possibly fit the model's limit burns money and quota on a guaranteed error. So the example roughly estimates tokens (the `characters / 4` heuristic — explicitly called approximate in the code) and compares it against the chosen model's `maxInputTokens` — that is the only message length limit, and it differs per model. Doesn't fit → "shorten it or split it into chunks", and **no request is sent**.

**It also reads the chosen model's price.** In `/models` every model declares `inputPricePer1kTokens`, `outputPricePer1kTokens` and `freeQuotaEligible`, so you can tell whether its tokens are billed **before** you send anything.

## Quick start

List the models without cloning anything (this call is free):

```bash
curl -H "Authorization: Bearer ak_sandbox_demo_mockdata_v1" \
     "https://atlorium.com/api/AiChat/models"
```

Send a message (this one is billed — see Pricing):

```bash
curl -X POST "https://atlorium.com/api/AiChat/send" \
     -H "Authorization: Bearer ak_sandbox_demo_mockdata_v1" \
     -H "Content-Type: application/json; charset=utf-8" \
     -d '{"message":"What is DNS?","sessionId":null,"model":"basic"}'
```

| Language | Run | Requires |
|----------|-----|----------|
| [Python](python/) | `pip install -r requirements.txt && python main.py` | Python 3.10+ |
| [TypeScript / Node.js](node/) | `npm install && npm start` | Node.js 20+ |
| [Go](go/) | `go run .` | Go 1.22+ |
| [Java](java/) | `java Main.java` | JDK 11+ (no dependencies) |
| [C#](csharp/) | `dotnet run` | .NET 8+ |
| [PHP](php/) | `php main.php` | PHP 8.1+ |

Pass your own text as an argument: `python main.py "text to summarize"`

## Authentication

The key goes in the `Authorization` header:

```
Authorization: Bearer YOUR_KEY
```

| Key | Behaviour |
|-----|-----------|
| `ak_sandbox_demo_mockdata_v1` | **Demo key.** Public, shared by everyone. No account, no charge, no real model call — `reply` comes back as a stub. Responses are deterministic: the same request always yields the same `sessionId` and the same token counts, so you can assert on them in tests. |
| Live key | Real model replies. Get one at [atlorium.com](https://atlorium.com) |

Switching to a live key requires **no code changes** — every example reads an environment variable:

```bash
export ATLORIUM_API_KEY="ak_your_live_key"
```

Sandbox `/send` responses carry the header `X-Atlorium-Sandbox: true`, so a stub can never be mistaken for a model reply.

## Endpoints

Base URL: `https://atlorium.com`

| Method | Path | Purpose | Billed |
|--------|------|---------|--------|
| `GET` | `/api/AiChat/models` | Available models with their limits and availability | No |
| `POST` | `/api/AiChat/send` | Send a message, get the model's reply | **Yes** |
| `GET` | `/api/AiChat/session/{sessionId}` | Session info: message count, activity timestamps | No |
| `DELETE` | `/api/AiChat/session/{sessionId}` | Delete the session and its history | No |

All four need a key (`401` without one), but only `/send` consumes quota — it is the one that reaches the model. Verified with live requests: `/models` and the session endpoints do not even carry the `X-Atlorium-Sandbox` header.

Paths are case-insensitive: `/api/aichat/send` works too.

### `POST /api/AiChat/send`

JSON body, UTF-8 (`Content-Type: application/json; charset=utf-8`).

| Field | Type | Description |
|-------|------|-------------|
| `message` | string | **Required.** Message text. The limit is expressed **in tokens** and depends on the model — check it against `maxInputTokens` from `/models`. Longer → `400`. |
| `sessionId` | string / null | Existing session to continue. `null` creates a new one. |
| `model` | string / null | Model `id` from `/models`. `null` uses the default. |

### `GET` / `DELETE` `/api/AiChat/session/{sessionId}`

| Parameter | In | Type | Description |
|-----------|----|------|-------------|
| `sessionId` | path | string | 32 hex characters — whatever `/send` returned |

## Response fields

### `GET /api/AiChat/models` → array

| Field | Type | Meaning |
|-------|------|---------|
| `id` | string | Identifier for the `model` field of `/send` |
| `name` | string | Human-readable name |
| `description` | string | How this model differs from the others |
| `isAvailable` | bool | **The key field.** Model is enabled server-side. Use only these. |
| `isLocal` | bool | Model runs on Atlorium's own server — the conversation never leaves it |
| `maxInputTokens` | int | Input limit — compare your text against it **before** sending. The only message length limit |
| `maxOutputTokens` | int | Maximum reply length |
| `contextWindowTokens` | int | Context window size: input + history + reply |
| `maxTokens` | int | Deprecated alias of `contextWindowTokens`, kept so older clients keep working. New code should use `contextWindowTokens` and `maxInputTokens` |
| `inputPricePer1kTokens` | number | Price of this model's input tokens, credits per 1000. `0` — the model's tokens are not billed |
| `outputPricePer1kTokens` | number | Price of output tokens, credits per 1000. Usually higher than input |
| `freeQuotaEligible` | bool | Whether the plan's daily included quota covers requests to this model |

### `POST /api/AiChat/send` → object

| Field | Type | Meaning |
|-------|------|---------|
| `sessionId` | string | Pass it to the next `/send` and the model sees the previous messages |
| `reply` | string | The model's reply. **A stub on the demo key** — see the warning above |
| `model` | string | Which model actually answered |
| `promptTokens` | int | Tokens spent on the prompt — the input part of the charge is computed from them |
| `completionTokens` | int | Tokens spent on the reply — the output part of the charge is computed from them |
| `totalTokens` | int | Sum of `promptTokens` and `completionTokens` |
| `createdAt` | date-time | Reply timestamp, UTC |

### `GET /api/AiChat/session/{sessionId}` → object

| Field | Type | Meaning |
|-------|------|---------|
| `sessionId` | string | Session identifier |
| `messageCount` | int | User messages so far (50 max) |
| `createdAt` | date-time | Creation time |
| `lastActivityAt` | date-time | Last activity. One hour idle and the session is deleted |

## Error handling

| Code | Cause | What to do |
|------|-------|------------|
| `400` | Message empty, longer than the chosen model's `maxInputTokens`, or a model that is not in `/models` | Shorten the text, or take an `id` from `/models`. This check runs **before** any money is reserved |
| `401` | Key missing, expired or invalid | Check the `Authorization` header |
| `402` | Balance does not cover the hold (see Pricing) | Top up at [atlorium.com](https://atlorium.com) |
| `404` | Session not found or expired | Expected: sessions live one hour. Start a new one with `sessionId: null` |
| `429` | Rate limit exceeded | Back off and retry — **with a ceiling**, see below |
| `500` | The model failed to answer, or the session already holds 50 user messages | Retry later, or start a new session (`sessionId: null`). **You are not charged for our failures**: the hold is only committed after a successful reply |
| `503` | The chosen model is temporarily unavailable (`isAvailable: false`) | Pick another model from `/models`. Nothing is charged |

All six examples map these codes to human-readable causes — see the `AtloriumError` class.

**About `429` and `Retry-After`.** When the hourly quota runs out, the server honestly asks you to wait 40+ minutes. A client that blindly sleeps for as long as it is told will hang for exactly that long. So the examples cap it: `MAX_RETRY_DELAY = 120` seconds — beyond that we report "quota exhausted" and exit. Exactly one retry, never a loop.

## Pricing and limits

**Pay-as-you-go, no subscription** — you pay for `/send` calls. `/models` and the session endpoints are not billed.

One `/send` costs **two** things:

1. **a per-request fee** — identical for every model;
2. **the token cost of the chosen model** — depends on the model and on how long the conversation is. When a model reports `inputPricePer1kTokens` and `outputPricePer1kTokens` as zero, this part disappears and you only pay the per-request fee. It is computed from the actual `promptTokens` / `completionTokens` in the response.

**What that means for your balance.** The token cost is only known after the model answers, but money has to be reserved before. So the maximum possible cost of the request (the whole allowed input plus the longest possible reply) is held first, then the actual amount is charged and the unused part of the hold is released immediately. Consequence: to **start** a request to an expensive model you need that maximum on the balance, even if the final charge is smaller. Not enough for the hold → `402`.

The `freeQuotaEligible` field in `/models` tells you whether the plan's daily included quota covers requests to that model.

Current prices: **[atlorium.com/pricing](https://atlorium.com/pricing)**

**This service has the tightest limits at Atlorium** — every call means real model work and real tokens. Limits are per IP and identical for the demo key and a registered user — the sandbox honestly shows the terms you will actually get.

**One run of an example = one billed `/send` call.** That is deliberate: the examples never loop over the API. Keep it in mind if you run them repeatedly — a `429` will arrive quickly, and that is the limit working, not a bug.

## FAQ

**Which model should I pick?** The one with `isAvailable: true` — and do not hardcode the `id`. The model set is server configuration and it changes. That is exactly why the examples read `/models` first.

**What is the "Private" model (`isLocal: true`)?** A model running on Atlorium's own hardware. The conversation never reaches an external provider — which matters when the text contains personal data or trade secrets.

**How do I build a chatbot with memory?** Take `sessionId` from the `/send` response and pass it to the next `/send`. The model sees the earlier messages. Up to 50 user messages per session; a session expires one hour after the last activity. To wipe it earlier: `DELETE /api/AiChat/session/{id}`.

**Can I count tokens up front?** Not exactly — tokenization is model-specific. A practical heuristic is `characters / 4`: close enough for English, usually an underestimate for Russian. Its job is not to compute billing but to reject obviously oversized text **before** the billed call. That is precisely how the examples use it.

**How much text can I send at once?** The cap is expressed **in tokens and depends on the model** — it is the `maxInputTokens` field in `/models`, and it differs between models. There is no single character-based cap: check against the chosen model's `maxInputTokens` (`400` beyond that). Larger inputs must be chunked and summarized piece by piece.

**How is this different from calling a foreign LLM provider directly?** The same key as the other 17 Atlorium APIs, one invoice, no separate foreign signup or card. And the server keeps the conversation history for you — you don't have to resend it on every request.

**Do I need to sign up to try it?** No. The demo key is public and works without an account — but it returns `reply` as a stub, not a model answer.

## Other Atlorium APIs

Same key, same account:

- [Image OCR](https://github.com/atlorium-api/image-ocr-api-client) — extract text from an image or Base64
- [Email verification](https://github.com/atlorium-api/email-verification-api-client) — syntax, MX records, disposable addresses
- [EGRUL/EGRIP](https://github.com/atlorium-api/egrul-api-client) — Russian company check by INN/OGRN: status, address, capital
- [Phone validation](https://github.com/atlorium-api/phone-validation-api-client) — format, line type, range operator
- [Image moderation](https://github.com/atlorium-api/image-moderation-api-client) — content, objects and text ON the image
- [Address standardization](https://github.com/atlorium-api/address-standardization-api-client) — parse a string into components, quality score

Full catalogue: [atlorium.com](https://atlorium.com)

## Links

- **API reference (Swagger):** [atlorium.com/aichatAPI](https://atlorium.com/aichatAPI)
- **OpenAPI spec:** [aichat_en-US.json](https://atlorium.com/openapi/aichat_en-US.json)
- **Support:** support@atlorium.com

## License

[MIT](LICENSE)
