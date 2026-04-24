# ai-bench Copilot Bridge (VSCode extension)

Tiny VSCode extension that exposes the `vscode.lm` Language Model API over a local Unix socket. Lets the ai-bench-java harness (JVM) drive GitHub Copilot as a solver without needing its own credentials — it piggybacks on whatever Copilot entitlement the signed-in VSCode already has.

## Protocol

JSON lines, one request and one response per line, over a Unix-domain socket (default `$TMPDIR/ai-bench-copilot.sock`).

Request:

```json
{ "id": "uuid", "model": "copilot", "system": "...", "messages": [{"role": "user", "content": "..."}], "temperature": 0.0, "maxTokens": 4096 }
```

Response:

```json
{ "id": "uuid", "content": "...", "modelIdentifier": "copilot/gpt-4/...", "promptTokens": 0, "completionTokens": 0 }
```

If the LLM call fails, the response contains an `error` field instead of `content`.

## Install

```
cd tooling/vscode-copilot-bridge
npm install
npm run compile
# then install the extension from its folder via VSCode: "Developer: Install Extension from Location"
```

## Settings

- `aibench.copilotBridge.socketPath` — override the socket path. Default `$TMPDIR/ai-bench-copilot.sock`.
- `aibench.copilotBridge.defaultModelFamily` — which `vscode.lm` family to request. Default `gpt-4`.

## Commands

- `ai-bench: Copilot Bridge Status` — shows whether the bridge is listening.
- `ai-bench: Restart Copilot Bridge` — close + rebind the socket.
