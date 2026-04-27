# ai-bench Copilot Bridge

Minimal VSCode extension that exposes the user's GitHub Copilot chat
entitlement over a local TCP endpoint (`127.0.0.1` + an OS-assigned port)
so the `ai-bench` harness can drive Copilot as an LLM provider.

The bridge originally used AF_UNIX sockets, but Node's libuv on some
Windows machines refuses AF_UNIX `bind()` with `EACCES` regardless of
path or ACLs (Java succeeds at the same paths — process-specific
policy interception we couldn't easily diagnose). TCP sidesteps the
issue without requiring administrator rights or AV exclusions.

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

Once installed the extension auto-starts, binds a TCP listener on
`127.0.0.1` with an OS-assigned port, and writes the chosen port to a
sidecar file at `~/.ai-bench-copilot.port` so other processes can
discover it. The `/llm` page in bench-webui shows a green check in
Step 3 of the Copilot wizard when a TCP connect to that port succeeds.

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
