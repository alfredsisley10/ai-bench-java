# ai-bench Copilot Bridge

Minimal VSCode extension that exposes the user's GitHub Copilot chat
entitlement over a local Unix socket so the `ai-bench` harness can drive
Copilot as an LLM provider.

## Build

```
cd tools/copilot-bridge-extension
npm install
npm run package        # produces copilot-bridge.vsix
```

## Install

The `.vsix` built above is served by `bench-webui` at
`GET /llm/copilot/download-bridge`. In VSCode: **Extensions → ⋯ →
Install from VSIX…** and pick the downloaded file.

Once installed the extension auto-starts and listens on
`/tmp/ai-bench-copilot.sock` (configurable). The `/llm` page in
bench-webui shows a green check in Step 3 of the Copilot wizard when
the socket is live.

## Wire protocol

One JSON object per line. Request:

```
{"model":"copilot","messages":[{"role":"user","content":"Hello"}]}
```

Response:

```
{"ok":true,"content":"Hi there!"}
```

On failure:

```
{"ok":false,"error":"no Copilot model available"}
```
