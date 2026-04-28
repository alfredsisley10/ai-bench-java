// Local OpenAI-compatible HTTP server backed by Copilot. Exposes
// /v1/models and /v1/chat/completions on a configurable port (default
// 11434 — same as Ollama's default, so OpenAI-compatible clients that
// detect Ollama by port pick this up automatically).
//
// Auth: bound to 127.0.0.1 only by default, no token. Bind address is
// configurable so you can put it behind your own ingress.

import * as vscode from 'vscode';
import * as http from 'http';
import * as crypto from 'crypto';
import { UsageTracker } from './usage';

let server: http.Server | undefined;

// In-memory auth state. Intentionally NOT persisted to disk, nor to
// VSCode globalState — a deliberate choice so a shared dev machine
// can't leak the token via ~/.vscode/settings.json or similar. Cleared
// on VSCode restart. The operator regenerates via the WebView.
//
// Default: no auth required. The HTTP server still refuses unauth
// requests when the bind address isn't loopback, so this is safe in
// the common (default) loopback configuration.
let authState: { token: string | null; required: boolean } = {
    token: null,
    required: false,
};

/** Current bind address the server was started with (null if stopped). */
let currentBindAddress: string | null = null;

export interface AuthStateView {
    /** True when a token exists (the value is exposed via a separate
     *  call so "view token" can be a distinct click). */
    tokenPresent: boolean;
    /** Whether the server enforces auth. If false AND the server is
     *  bound to a loopback address, requests are allowed unauthenticated. */
    required: boolean;
    /** True when the current bind address is loopback — informs the UI
     *  whether "allow anonymous" is even a safe option. */
    bindIsLoopback: boolean;
    /** Masked preview of the token (first 8 chars) for the UI. */
    tokenPreview: string;
}

export function getAuthState(): AuthStateView {
    return {
        tokenPresent: authState.token !== null,
        required: authState.required,
        bindIsLoopback: currentBindAddress ? isLoopback(currentBindAddress) : true,
        tokenPreview: authState.token
            ? authState.token.slice(0, 8) + '…' + authState.token.slice(-4)
            : '',
    };
}

/** Returns the raw token so the UI's "copy / reveal" button can use it.
 *  Scope-limited to the local extension host only. */
export function getAuthToken(): string | null { return authState.token; }

export function setAuthToken(token: string | null): void {
    authState.token = token && token.trim() ? token.trim() : null;
}

export function setAuthRequired(required: boolean): void {
    authState.required = !!required;
}

/** Mint a fresh random token. Format matches OpenAI's convention
 *  (`sk-…`) so clients that validate the prefix accept it. */
export function generateAuthToken(): string {
    const bytes = crypto.randomBytes(32).toString('base64url');
    authState.token = 'sk-aibench-' + bytes;
    return authState.token;
}

function isLoopback(address: string): boolean {
    const a = (address || '').toLowerCase();
    return a === '127.0.0.1' || a === '::1' || a === 'localhost';
}

interface ServerArgs {
    port: number;
    bindAddress: string;
    pickModel: (req: { model?: string }) => Promise<vscode.LanguageModelChat | undefined>;
    enumerateModels: () => Promise<Array<{ id: string }>>;
    tracker: UsageTracker;
    /** Per-request stage logger from extension.ts. Wraps an async fn
     *  with start/finish + 10-second heartbeat lines so the operator
     *  can see WHICH stage is hung when a chat request stalls. */
    logStage: <T>(reqId: string, stage: string, fn: () => Promise<T>) => Promise<T>;
    /** Plain log line, same OutputChannel as logStage uses. */
    log: (msg: string) => void;
    /** Mint a per-request id for correlating log lines. */
    newReqId: () => string;
}

function readBody(req: http.IncomingMessage): Promise<string> {
    return new Promise((resolve, reject) => {
        const chunks: Buffer[] = [];
        req.on('data', c => chunks.push(Buffer.isBuffer(c) ? c : Buffer.from(c)));
        req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
        req.on('error', reject);
    });
}

function send(res: http.ServerResponse, status: number, body: object): void {
    const data = JSON.stringify(body);
    res.writeHead(status, {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(data),
    });
    res.end(data);
}

export async function startOpenAiServer(args: ServerArgs): Promise<void> {
    if (server) {
        vscode.window.showInformationMessage('OpenAI-compatible endpoint already running.');
        return;
    }

    server = http.createServer(async (req, res) => {
        try {
            // CORS for browser-based clients.
            res.setHeader('Access-Control-Allow-Origin', '*');
            res.setHeader('Access-Control-Allow-Headers', '*');
            res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
            if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }

            // Bearer-token auth. Requests are allowed without a token
            // only when (a) auth is explicitly disabled AND (b) the
            // endpoint is bound to a loopback address — so a misconfig
            // can never expose an unauthenticated endpoint on a LAN.
            const bindLoopback = isLoopback(args.bindAddress);
            const allowAnonymous = !authState.required && bindLoopback;
            if (!allowAnonymous) {
                if (!authState.token) {
                    return send(res, 503, {
                        error: {
                            message: 'Auth required but no token is configured — set one via the VSCode extension',
                            type: 'no_auth_configured',
                        },
                    });
                }
                const headerAuth = String(req.headers['authorization'] ?? '');
                if (headerAuth !== `Bearer ${authState.token}`) {
                    res.setHeader('WWW-Authenticate', 'Bearer realm="ai-bench-copilot"');
                    return send(res, 401, {
                        error: {
                            message: 'Missing or invalid Authorization: Bearer <token>',
                            type: 'unauthorized',
                        },
                    });
                }
            }

            const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
            const client = String(req.headers['x-client-id'] || req.socket.remoteAddress || 'openai-http');

            if (req.method === 'GET' && (url.pathname === '/v1/models' || url.pathname === '/models')) {
                const models = await args.enumerateModels();
                return send(res, 200, {
                    object: 'list',
                    data: models.map(m => ({
                        id: m.id, object: 'model', created: 0, owned_by: 'github-copilot'
                    })),
                });
            }

            // Per-runId activity query. Returns the authoritative token
            // totals + per-model breakdown for a single BenchmarkRun.id
            // (or whatever caller-correlation slug was tagged on
            // record()), so the test harness can ASK THE BRIDGE for the
            // truth instead of computing approximations locally.
            //   GET /v1/activity                  -> distinct runIds list
            //   GET /v1/activity?runId=<id>       -> per-run snapshot
            if (req.method === 'GET' && (url.pathname === '/v1/activity' || url.pathname === '/activity')) {
                const wanted = url.searchParams.get('runId');
                if (!wanted) {
                    return send(res, 200, {
                        runIds: args.tracker.knownRunIds(),
                        note: 'GET this endpoint with ?runId=<id> for per-run totals.',
                    });
                }
                const snap = args.tracker.snapshotForRunId(wanted);
                if (snap == null) {
                    return send(res, 404, {
                        error: { message: `No activity recorded for runId=${wanted}`,
                                 type: 'no_run_activity' },
                        runId: wanted,
                    });
                }
                return send(res, 200, snap);
            }

            if (req.method === 'POST' && (url.pathname === '/v1/chat/completions' || url.pathname === '/chat/completions')) {
                const reqId = args.newReqId();
                const runIdHeader = req.headers['x-run-id'] != null ? String(req.headers['x-run-id']) : '-';
                args.log(`[${reqId}] ← HTTP /v1/chat/completions client=${client} runId=${runIdHeader}`);
                const raw = await readBody(req);
                const body = raw ? JSON.parse(raw) : {};
                const model = await args.logStage(reqId, 'pickModel', () => args.pickModel({ model: body.model }));
                if (!model) {
                    args.log(`[${reqId}] ✗ no model available`);
                    return send(res, 503, {
                        error: { message: 'no Copilot model available — start the bridge and grant consent', type: 'no_model' }
                    });
                }

                const inputMessages = (body.messages ?? []).map((m: any) =>
                    m.role === 'assistant'
                        ? vscode.LanguageModelChatMessage.Assistant(m.content)
                        : vscode.LanguageModelChatMessage.User(m.content));
                const promptText = (body.messages ?? []).map((m: any) => String(m.content ?? '')).join('\n');
                args.log(`[${reqId}] selected model=${model.id} (${model.name}); ${inputMessages.length} message(s), ${promptText.length} prompt chars`);

                const token = new vscode.CancellationTokenSource().token;
                const resp = await args.logStage(reqId, `model.sendRequest(${model.id})`,
                    () => model.sendRequest(inputMessages, {}, token));
                let content = '';
                let frags = 0;
                await args.logStage(reqId, 'stream response.text',
                    async () => { for await (const frag of resp.text) { content += frag; frags++; } });
                args.log(`[${reqId}] streamed ${frags} fragments, ${content.length} chars total → response sent`);

                args.tracker.record({
                    modelId: model.id, client,
                    promptText, completionText: content,
                    via: 'openai-http',
                    // X-Run-Id is the HTTP-equivalent of the socket
                    // protocol's `runId` JSON field. Tag every call
                    // the harness makes for a specific BenchmarkRun
                    // so the activity panel can filter to that run
                    // and so the harness can query authoritative
                    // totals via GET /v1/activity?runId=.
                    runId: (req.headers['x-run-id'] != null
                        ? String(req.headers['x-run-id']) : undefined),
                });

                return send(res, 200, {
                    id: 'chatcmpl-' + Date.now().toString(36),
                    object: 'chat.completion',
                    created: Math.floor(Date.now() / 1000),
                    model: model.id,
                    choices: [{
                        index: 0,
                        message: { role: 'assistant', content },
                        finish_reason: 'stop',
                    }],
                    // usage fields are approximations, see /llm panel for details
                    usage: {
                        prompt_tokens: Math.ceil(promptText.length / 4),
                        completion_tokens: Math.ceil(content.length / 4),
                        total_tokens: Math.ceil((promptText.length + content.length) / 4),
                        approximation: 'char-based ~4 chars/token',
                    },
                });
            }

            return send(res, 404, { error: { message: `not found: ${req.method} ${url.pathname}`, type: 'not_found' } });
        } catch (e: any) {
            send(res, 500, { error: { message: String(e?.message ?? e), type: 'internal_error' } });
        }
    });

    currentBindAddress = args.bindAddress;
    await new Promise<void>((resolve, reject) => {
        const onError = (err: NodeJS.ErrnoException) => {
            // EADDRINUSE: a previous bridge instance (orphaned after a
            // reload that didn't run our deactivate hook), Ollama (which
            // also defaults to 11434), or a manually-launched process is
            // already on the port. Surface the conflict explicitly with
            // remediation steps and clear the partial server reference
            // so the operator can retry after freeing the port without
            // hitting the "already running" guard at the top of this
            // function.
            if (err.code === 'EADDRINUSE') {
                args.log(`OpenAI HTTP shim startup FAILED: port ${args.port} is already in use on ${args.bindAddress}.`);
                args.log(`  Resolve options:`);
                args.log(`  (1) Find the process holding the port and kill it. On macOS/Linux:`);
                args.log(`        lsof -iTCP:${args.port} -sTCP:LISTEN`);
                args.log(`        kill <PID>`);
                args.log(`      On Windows (PowerShell):`);
                args.log(`        Get-NetTCPConnection -LocalPort ${args.port} -State Listen | Select OwningProcess`);
                args.log(`        Stop-Process -Id <PID>`);
                args.log(`  (2) Ollama defaults to port ${args.port}. If you have Ollama installed, either stop it`);
                args.log(`      ('ollama serve' / 'killall ollama') or change the bridge's port via`);
                args.log(`      VSCode settings: 'aiBench.copilotBridge.openAiPort' (e.g. 11435).`);
                args.log(`  (3) If a previous VSCode extension host crashed without releasing the port,`);
                args.log(`      Developer: Reload Window once the orphaned process is killed.`);
            }
            // Drop the server reference so the public 'already running'
            // guard at the top of startOpenAiServer doesn't block a retry.
            server = undefined;
            currentBindAddress = null;
            reject(err);
        };
        server!.once('error', onError);
        server!.listen(args.port, args.bindAddress, () => {
            // Successful bind — drop the error handler so a later
            // runtime error doesn't fire the same recovery path.
            server!.removeListener('error', onError);
            const authDesc = authState.required
                ? (authState.token ? 'with Bearer auth' : 'with Bearer auth (token NOT SET yet — endpoint will 503)')
                : (isLoopback(args.bindAddress)
                    ? 'unauthenticated (loopback-only)'
                    : 'unauthenticated BUT NOT ON LOOPBACK — refusing requests');
            vscode.window.showInformationMessage(
                `OpenAI-compatible endpoint listening on http://${args.bindAddress}:${args.port}/v1/ — ${authDesc}`);
            args.log(`OpenAI HTTP shim listening on http://${args.bindAddress}:${args.port}/v1/`);
            resolve();
        });
    });
}

export function stopOpenAiServer(): void {
    if (!server) return;
    server.close();
    server = undefined;
    currentBindAddress = null;
    vscode.window.showInformationMessage('OpenAI-compatible endpoint stopped.');
}

export function isOpenAiServerRunning(): boolean { return server !== undefined; }
