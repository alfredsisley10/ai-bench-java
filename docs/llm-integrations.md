# LLM integrations

Two enterprise-first adapters ship built in. Both implement the same `LlmClient` interface in `harness-llm/llm-api`, so the rest of the harness stays agnostic.

## 1. GitHub Copilot (via VSCode companion extension)

Copilot has no public HTTP API. The reliable programmatic path is VSCode's `vscode.lm.selectChatModels(...)` → `model.sendRequest(...)` in an extension.

### Architecture

```
┌─────────────────────┐   Unix socket    ┌────────────────────────┐
│ harness (JVM)       │ ───────────────► │ VSCode extension       │
│  llm-copilot client │  JSON-over-line  │  ai-bench-copilot      │
└─────────────────────┘                  │  uses vscode.lm.*      │
                                         └───────────┬────────────┘
                                                     │
                                                     ▼
                                              GitHub Copilot (user's
                                              own entitlement)
```

- The VSCode extension lives at `tooling/vscode-copilot-bridge/` (separate directory, not included in the main Gradle build).
- On activation it opens a Unix socket at `$TMPDIR/ai-bench-copilot.sock`, listens for JSONL requests `{id, messages, model}`, and relays them to `vscode.lm`.
- The JVM side speaks the same protocol via `llm-copilot/CopilotSocketClient.kt`.
- No user credentials are stored or forwarded — the extension runs under the user's signed-in VSCode and piggybacks on whatever Copilot entitlement they have.

### Why a socket

Avoids HTTP port allocation (some corporate VSCode builds lock down loopback listeners), and the Unix socket's filesystem permissions give the harness a simple authentication boundary (only the same user can connect).

### Limitations

- Copilot sets its own token accounting; we record `tokens_reported_by_model` plus our own `tokens_estimated_bpe`.
- Session requires VSCode running with the extension active.

## 2. Corporate OpenAI-spec API (Apigee-fronted)

Banks commonly expose an internal LLM behind an API gateway. The typical shape:

1. **Apigee OAuth2 client_credentials flow** → short-lived bearer token.
2. **Per-request custom headers** beyond `Authorization: Bearer <token>`:
   - `X-Correlation-Id` (uuid per request, for audit trail)
   - `X-Client-Id` (registered app id)
   - `X-Environment` (dev/qa/prod)
   - `X-Use-Case` (categorization for cost chargeback)
   - Plus any bank-specific headers
3. **Base URL** is the corporate gateway, e.g. `https://api.bank-internal.com/llm/v1`.
4. **Response payloads** are OpenAI-compatible (chat completions JSON shape).

### Config

`~/.ai-bench/corp-openai.yaml`:

```yaml
base_url: https://api.bank-internal.com/llm/v1
apigee:
  token_url: https://api.bank-internal.com/oauth2/token
  client_id_env: CORP_LLM_CLIENT_ID
  client_secret_env: CORP_LLM_CLIENT_SECRET
  scope: llm.invoke
  token_cache_ttl_sec: 1800

default_model: gpt-4o-corp

headers:
  X-Client-Id: ai-bench
  X-Environment: dev
  X-Use-Case: benchmarking
  # X-Correlation-Id is auto-generated per request

proxy:
  url_env: HTTPS_PROXY       # respected if set
  # truststore path for corporate CA:
  truststore: ~/.ai-bench/corp-ca.jks
  truststore_password_env: CORP_TRUSTSTORE_PASSWORD
```

### Client behavior

- Lazy token acquisition; cache until `expires_in - 60s`.
- Retry-once on 401 (refresh token, replay).
- Honest token counting from response `usage` field.
- Respects standard `HTTPS_PROXY`/`NO_PROXY` env, plus pluggable `ProxySelector`.
- Trusts the configured corporate CA bundle — no global JVM truststore mutation.

### Code

`llm-corp-openai/CorpOpenAiClient.kt` — implementation; `llm-corp-openai/ApigeeTokenProvider.kt` — token mgmt.

## Adding a new adapter

Implement `LlmClient` (`suspend fun complete(request: LlmRequest): LlmResponse`), register it in `LlmClientFactory`, and add a config section. The rest of the harness calls through the interface.
