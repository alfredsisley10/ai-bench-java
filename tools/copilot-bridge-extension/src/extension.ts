// ai-bench Copilot Bridge — listens on a local TCP endpoint
// (`127.0.0.1` + OS-assigned port) and relays bench-harness requests to
// the VSCode Language Model Chat API (vscode.lm). The bound port is
// written to `~/.ai-bench-copilot.port` so other processes (bench-webui,
// bench-harness) can discover where to connect.
//
// Wire format: one JSON object per line, LF-terminated. Request carries
// an `op` tag that switches handlers:
//
//   {"op":"list-models"}
//     -> {"ok":true, "models":[{id,name,vendor,family,version,maxInputTokens,auto}...],
//                    "auto":"<id-that-auto-resolves-to>"}
//
//   {"op":"chat","model":"auto"|"<id>","client":"<id>","messages":[...]}
//     -> {"ok":true,"content":"...","modelId":"<resolved-id>"}
//
// Also exposes:
//   - VSCode WebView dashboard with usage stats (per-model, per-client,
//     estimated cost) — opened via "ai-bench: Open Bridge Stats"
//   - Optional local OpenAI-compatible HTTP endpoint on port 11434 —
//     toggled via "ai-bench: Start/Stop Local OpenAI Endpoint"

import * as vscode from 'vscode';
import * as net from 'net';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { UsageTracker } from './usage';
import * as pricingMod from './pricing';
import { openStatsPanel, pushUpdate, RuntimeState, WebviewHooks } from './webview';
import {
    startOpenAiServer, stopOpenAiServer, isOpenAiServerRunning,
    getAuthState, getAuthToken, setAuthToken, setAuthRequired, generateAuthToken,
} from './openai-server';
import { BridgeTreeProvider } from './tree-view';

let server: net.Server | undefined;
// Tracks whether the server actually completed `listen()` and is bound to
// a TCP port. Distinct from `server !== undefined` because Node assigns
// the Server object synchronously from `net.createServer`, but the bind is
// async and can fail without ever firing the listen callback. The webview /
// tree-view / status bar consult THIS flag, not `server`, so the GUI never
// shows "running" when the kernel has no listener.
let bridgeListening = false;
let tracker: UsageTracker | undefined;
let statusBar: vscode.StatusBarItem | undefined;
let extensionContext: vscode.ExtensionContext | undefined;
let webviewHooks: WebviewHooks | undefined;
let treeProvider: BridgeTreeProvider | undefined;
let output: vscode.OutputChannel | undefined;

/** Dump to both an OutputChannel and the extension-host console so
 *  operators can pop open View → Output → "ai-bench Copilot Bridge"
 *  when something goes wrong and see the exact trace. */
function log(msg: string): void {
    const line = `[${new Date().toISOString()}] ${msg}`;
    if (output) output.appendLine(line);
    console.log(line);
}

function logError(prefix: string, e: unknown): void {
    const err = e as any;
    const stack = err?.stack ?? String(err);
    log(`ERROR ${prefix}: ${err?.message ?? err}\n${stack}`);
}

/** Per-request id for correlating log lines across stages. Monotonic
 *  counter; resets every extension-host launch. */
let nextRequestId = 1;
function newReqId(): string { return `r${nextRequestId++}`; }

/**
 * Run an async stage with start/finish + 10-second heartbeat
 * logging. The heartbeat is the key diagnostic for the
 * "bridge accepted but never replied" failure mode: every stage
 * emits a [reqId] still running after Ns line every 10 seconds
 * it's blocked, so the operator can pinpoint exactly which stage
 * is stuck (model.sendRequest stalling on Copilot vs. response
 * streaming hung on the SSE side, etc.) just from View -> Output
 * -> ai-bench Copilot Bridge.
 */
// fn is typed as PromiseLike<T> (rather than Promise<T>) so call sites
// can pass VSCode API methods that return Thenable<T> -- e.g.
// model.sendRequest(...) -- without an explicit Promise.resolve()
// wrapper. PromiseLike is structurally identical to vscode.Thenable.
async function logStage<T>(reqId: string, stage: string, fn: () => PromiseLike<T>): Promise<T> {
    const start = Date.now();
    log(`[${reqId}] → ${stage}`);
    const heartbeat = setInterval(() => {
        const elapsed = Math.round((Date.now() - start) / 1000);
        log(`[${reqId}] … ${stage} still running after ${elapsed}s`);
    }, 10000);
    try {
        const result = await fn();
        clearInterval(heartbeat);
        log(`[${reqId}] ✓ ${stage} done in ${Date.now() - start}ms`);
        return result;
    } catch (e: any) {
        clearInterval(heartbeat);
        log(`[${reqId}] ✗ ${stage} threw after ${Date.now() - start}ms: ${e?.message ?? e}`);
        throw e;
    }
}

// Re-entry guard for notifyStateChanged. Cheap insurance against a
// future accidental cycle (such as the one that was just fixed).
let inNotify = false;
function notifyStateChanged(): void {
    if (inNotify) { log('notifyStateChanged: re-entry blocked'); return; }
    inNotify = true;
    try {
        try { if (webviewHooks) pushUpdate(webviewHooks); } catch (e) { logError('pushUpdate', e); }
        try { treeProvider?.refresh(); } catch (e) { logError('treeRefresh', e); }
        try { refreshStatusBar(); } catch (e) { logError('statusBar', e); }
    } finally {
        inNotify = false;
    }
}

interface ModelInfo {
    id: string; name: string; vendor: string; family: string;
    version: string; maxInputTokens: number; auto: boolean;
}

async function enumerateModels(): Promise<ModelInfo[]> {
    const isConsentRetryable = (err: unknown): boolean => {
        const msg = String((err as any)?.message ?? err ?? '').toLowerCase();
        return msg.includes('no messages')
            || msg.includes('consent')
            || msg.includes('not yet authorized')
            || msg.includes('user did not consent');
    };
    let lastErr: unknown = undefined;
    for (let attempt = 0; attempt < 4; attempt++) {
        try {
            const models = await vscode.lm.selectChatModels({ vendor: 'copilot' });
            return models.map((m, idx) => ({
                id: m.id, name: m.name, vendor: m.vendor, family: m.family,
                version: m.version, maxInputTokens: m.maxInputTokens,
                auto: idx === 0
            }));
        } catch (e) {
            lastErr = e;
            if (!isConsentRetryable(e)) throw e;
            await new Promise(r => setTimeout(r, 500 * (attempt + 1)));
        }
    }
    throw lastErr ?? new Error('selectChatModels failed for unknown reason');
}

async function pickModel(req: { model?: string }): Promise<vscode.LanguageModelChat | undefined> {
    const all = await vscode.lm.selectChatModels({ vendor: 'copilot' });
    if (all.length === 0) return undefined;
    if (!req.model || req.model === 'auto') return all[0];
    // Exact-match first (cheapest, no surprises).
    const exact = all.find(m => m.id === req.model);
    if (exact) return exact;
    // Fuzzy: strip the optional "copilot-" prefix, normalize dashes
    // to dots so callers using "copilot-gpt-4-1" reach VSCode's
    // "gpt-4.1". The mapping is conservative -- a request that
    // doesn't normalize to ANY available id falls through to a
    // strict no-match (returns undefined) rather than silently
    // routing to all[0], which historically misrouted gpt-4.1
    // requests to the first listed model (typically a premium
    // claude/gpt-5 variant) and burned the operator's quota
    // without any visibility.
    const normalize = (s: string) =>
        s.toLowerCase().replace(/^copilot-/, '').replace(/-/g, '.');
    const want = normalize(req.model);
    const fuzzy = all.find(m => normalize(m.id) === want);
    if (fuzzy) {
        log(`pickModel: fuzzy '${req.model}' -> '${fuzzy.id}'`);
        return fuzzy;
    }
    log(`pickModel: '${req.model}' not found and no fuzzy match. ` +
        `Available: ${all.map(m => m.id).join(', ')}. Returning undefined ` +
        `so caller gets a clear 4xx instead of a silent wrong-model call.`);
    return undefined;
}

async function handleSocketRequest(raw: string, socket: net.Socket): Promise<void> {
    const reqId = newReqId();
    let req: any;
    try { req = JSON.parse(raw); } catch (e: any) {
        log(`[${reqId}] ✗ rejected on JSON parse: ${e.message}`);
        socket.write(JSON.stringify({ ok: false, error: 'invalid JSON: ' + e.message }) + '\n');
        return;
    }
    try {
        const op = req.op ?? 'chat';
        const client = String(req.client ?? socket.remoteAddress ?? 'socket-anonymous');
        log(`[${reqId}] ← TCP op=${op} client=${client} runId=${req.runId ?? '-'} model=${req.model ?? 'auto'}`);
        switch (op) {
            case 'list-models': {
                const models = await logStage(reqId, 'enumerateModels', () => enumerateModels());
                socket.write(JSON.stringify({
                    ok: true, models, auto: models[0]?.id ?? null, count: models.length
                }) + '\n');
                log(`[${reqId}] → response sent (${models.length} models)`);
                return;
            }
            case 'chat': {
                const model = await logStage(reqId, 'pickModel', () => pickModel(req));
                if (!model) {
                    log(`[${reqId}] ✗ no model available`);
                    socket.write(JSON.stringify({ ok: false,
                        error: 'no Copilot model available — check that the GitHub Copilot Chat extension is installed and you are signed in'
                    }) + '\n');
                    return;
                }
                const inputMessages = (req.messages ?? []).map((m: any) =>
                    m.role === 'assistant'
                        ? vscode.LanguageModelChatMessage.Assistant(m.content)
                        : vscode.LanguageModelChatMessage.User(m.content));
                const promptText = (req.messages ?? []).map((m: any) => String(m.content ?? '')).join('\n');
                log(`[${reqId}] selected model=${model.id} (${model.name}); ${inputMessages.length} message(s), ${promptText.length} prompt chars`);

                const tok = new vscode.CancellationTokenSource().token;
                const resp = await logStage(reqId, `model.sendRequest(${model.id})`,
                    () => model.sendRequest(inputMessages, {}, tok));
                let content = '';
                let frags = 0;
                await logStage(reqId, 'stream response.text',
                    async () => { for await (const frag of resp.text) { content += frag; frags++; } });
                log(`[${reqId}] streamed ${frags} fragments, ${content.length} chars total`);

                tracker?.record({
                    modelId: model.id, client, promptText, completionText: content, via: 'socket',
                    // Caller-supplied correlation id (e.g. BenchmarkRun.id).
                    // Recorded so the webview can filter "all calls for
                    // this run" and the harness can later query the
                    // bridge for authoritative totals via /v1/activity.
                    runId: typeof req.runId === 'string' ? req.runId : undefined,
                });
                treeProvider?.refresh();
                refreshStatusBar();

                socket.write(JSON.stringify({
                    ok: true, content, modelId: model.id, modelName: model.name
                }) + '\n');
                log(`[${reqId}] → response sent (${content.length} chars)`);
                return;
            }
            // Query usage stats scoped to a specific runId. Lets the
            // test harness ask the bridge for AUTHORITATIVE token
            // totals tagged to a BenchmarkRun, instead of trusting
            // per-call response counts (which are approximations) or
            // local-only synthetic stats from BenchmarkRunService.
            //   request:  { op: "query-activity", runId: "run-abc123" }
            //   response: { ok: true, snapshot: {...}, runIds: null }
            // Or, with no runId, returns the list of distinct runIds
            // currently in the bridge's record buffer:
            //   request:  { op: "query-activity" }
            //   response: { ok: true, snapshot: null, runIds: [...] }
            case 'query-activity': {
                const wanted = typeof req.runId === 'string' ? req.runId : null;
                if (wanted == null) {
                    socket.write(JSON.stringify({
                        ok: true,
                        snapshot: null,
                        runIds: tracker?.knownRunIds() ?? [],
                    }) + '\n');
                    return;
                }
                const snap = tracker?.snapshotForRunId(wanted) ?? null;
                socket.write(JSON.stringify({
                    ok: true,
                    snapshot: snap,
                    runIds: null,
                }) + '\n');
                return;
            }
            default:
                socket.write(JSON.stringify({ ok: false,
                    error: `unknown op '${op}' — expected 'chat', 'list-models', or 'query-activity'` }) + '\n');
        }
    } catch (e: any) {
        logError(`[${reqId}] handler threw`, e);
        socket.write(JSON.stringify({ ok: false, error: String(e?.message ?? e) }) + '\n');
    }
}

/**
 * Sidecar file the bridge writes after it picks an OS-assigned port.
 * Bench-webui and the bench-harness read this file to discover where to
 * connect. Single line of text: the decimal port number.
 *
 * The bridge originally tried AF_UNIX, but on some Windows machines Node's
 * libuv returns EACCES on `bind()` regardless of path or ACLs, while Java
 * binds the same path fine — process-specific policy interception that we
 * can't easily diagnose. TCP localhost sidesteps the issue: any Node app
 * on the box can bind a high port on 127.0.0.1.
 */
function portFilePath(): string {
    return path.join(os.homedir(), '.ai-bench-copilot.port');
}

async function startBridge(context: vscode.ExtensionContext): Promise<void> {
    log('startBridge: enter (server=' + (server ? 'set' : 'null')
        + ', listening=' + bridgeListening + ')');
    if (server) {
        log('startBridge: server already set — refusing duplicate start');
        vscode.window.showInformationMessage('Copilot Bridge already running.');
        return;
    }
    server = net.createServer((socket) => {
        let buffer = '';
        socket.on('data', async (chunk) => {
            buffer += chunk.toString('utf8');
            let nl = buffer.indexOf('\n');
            while (nl >= 0) {
                const line = buffer.slice(0, nl);
                buffer = buffer.slice(nl + 1);
                nl = buffer.indexOf('\n');
                if (line.trim()) await handleSocketRequest(line, socket);
            }
        });
    });
    // Capture bind errors that would otherwise vanish — without this
    // handler a failed listen() emits 'error' which Node treats as
    // unhandled and which never reaches the user.
    server.on('error', (err: NodeJS.ErrnoException) => {
        log('startBridge: server emitted error — code=' + err.code
            + ' errno=' + err.errno + ' syscall=' + err.syscall
            + ' msg=' + err.message);
        bridgeListening = false;
        const dead = server;
        server = undefined;
        try { dead?.close(); } catch { /* ok — already broken */ }
        try { fs.unlinkSync(portFilePath()); } catch { /* ok */ }
        try {
            vscode.window.showErrorMessage(
                `Copilot Bridge failed to bind: ${err.code ?? ''} ${err.message}`,
                'Open Output'
            ).then(choice => { if (choice === 'Open Output' && output) output.show(true); });
        } catch { /* ok */ }
        notifyStateChanged();
    });
    log('startBridge: calling server.listen({host:127.0.0.1, port:0})');
    server.listen({ host: '127.0.0.1', port: 0 }, async () => {
        const addr = server!.address() as net.AddressInfo;
        bridgeListening = true;
        log('startBridge: listen callback fired — bound to 127.0.0.1:' + addr.port);
        try {
            fs.writeFileSync(portFilePath(), String(addr.port), { encoding: 'utf8', mode: 0o600 });
            log('startBridge: wrote port to ' + portFilePath());
        } catch (e: any) {
            log('startBridge: WARN — could not write port file: ' + (e?.message ?? e));
        }
        try {
            const models = await enumerateModels();
            log('startBridge: ' + models.length + ' model(s) available');
            vscode.window.showInformationMessage(
                `Copilot Bridge listening on 127.0.0.1:${addr.port} — ${models.length} model(s) available` +
                (models[0] ? `; auto → ${models[0].name}` : ''));
        } catch (e: any) {
            log('startBridge: model enumeration deferred — ' + (e?.message ?? e));
            vscode.window.showInformationMessage(
                `Copilot Bridge listening on 127.0.0.1:${addr.port} (model enumeration deferred until consent grant)`);
        }
        notifyStateChanged();
    });
    context.subscriptions.push({ dispose: () => stopBridge() });
}

// Hard re-entry fuse. If anything in the call chain below invokes
// stopBridge recursively, this short-circuits to prevent a stack
// overflow AND surfaces a clear message in the output channel so we
// can diagnose the cycle.
let inStopBridge = false;
function stopBridge(): void {
    if (inStopBridge) {
        log('stopBridge: RE-ENTRY BLOCKED — returning without action');
        return;
    }
    inStopBridge = true;
    log('stopBridge: enter (server=' + (server ? 'set' : 'null') + ')');
    try {
        const s = server;
        server = undefined;
        bridgeListening = false;
        if (s) {
            try { s.close(); log('stopBridge: server.close() called'); }
            catch (e) { logError('stopBridge s.close', e); }
            try {
                fs.unlinkSync(portFilePath());
                log('stopBridge: portFile unlinked');
            } catch { /* not present is fine */ }
            try { vscode.window.showInformationMessage('Copilot Bridge stopped.'); }
            catch (e) { logError('stopBridge info', e); }
        }
        try { notifyStateChanged(); } catch (e) { logError('stopBridge notify', e); }
    } catch (e) {
        logError('stopBridge OUTER', e);
    } finally {
        inStopBridge = false;
        log('stopBridge: exit');
    }
}

function getRuntimeState(): RuntimeState {
    const cfg = vscode.workspace.getConfiguration('aiBench.copilotBridge');
    const port = cfg.get<number>('openAiPort') ?? 11434;
    const bind = cfg.get<string>('openAiBindAddress') ?? '127.0.0.1';
    // Reflect the actual kernel-level bind, not just whether the Server
    // object exists. The display string is the host:port the bridge bound
    // to, sourced from the live server.address() so it tracks restarts.
    let bridgeAddr = '';
    if (bridgeListening && server) {
        const a = server.address();
        if (a && typeof a === 'object') bridgeAddr = `127.0.0.1:${a.port}`;
    }
    return {
        bridgeRunning: bridgeListening,
        openAiRunning: isOpenAiServerRunning(),
        socketPath: bridgeAddr,
        openAiUrl: isOpenAiServerRunning() ? `http://${bind}:${port}/v1/` : null,
        auth: getAuthState(),
    };
}

function refreshStatusBar(): void {
    if (!statusBar || !tracker) return;
    const s = tracker.snapshot();
    statusBar.text = `$(symbol-event) Copilot: ${s.totalRequests} req · $${s.totalEstimatedCostUsd.toFixed(4)}`;
    statusBar.tooltip = 'Click to open ai-bench Copilot Bridge usage stats';
}

async function startOpenAiFromConfig(): Promise<void> {
    const cfg = vscode.workspace.getConfiguration('aiBench.copilotBridge');
    await startOpenAiServer({
        port: cfg.get<number>('openAiPort') ?? 11434,
        bindAddress: cfg.get<string>('openAiBindAddress') ?? '127.0.0.1',
        pickModel,
        enumerateModels: async () => (await enumerateModels()).map(m => ({ id: m.id })),
        tracker: tracker!,
        // Per-request stage logging passed in so the HTTP shim's logs
        // land on the same OutputChannel as the TCP socket's, with
        // the same [reqId] correlation format.
        logStage,
        log,
        newReqId,
        // Pricing endpoint persists POSTed overrides via globalState;
        // pass the live extension context so they survive reload.
        context: extensionContext ?? null,
    });
    notifyStateChanged();
}

function stopOpenAiAndNotify(): void {
    stopOpenAiServer();
    notifyStateChanged();
}

/**
 * Watch for in-place upgrades of this extension. When a user installs
 * a newer VSIX over the running version, VSCode loads the new files to
 * disk but the OLD extension code stays in the running extension host
 * until the window is reloaded. Most users miss VSCode's subtle
 * "Reload Required" pill in the Extensions panel; this notification
 * makes the action unmissable with a one-click Reload button.
 */
function watchForExtensionUpdates(context: vscode.ExtensionContext): void {
    const ME = 'ai-bench.copilot-bridge';
    const runningVersion = vscode.extensions.getExtension(ME)?.packageJSON?.version ?? '0.0.0';

    // Persist the version we activated as so we can also detect a
    // first-launch-after-update (notify once on the new code).
    const lastSeenKey = 'aiBench.copilotBridge.lastSeenVersion';
    const lastSeen = context.globalState.get<string>(lastSeenKey);
    if (lastSeen && lastSeen !== runningVersion) {
        vscode.window.showInformationMessage(
            `ai-bench Copilot Bridge updated to v${runningVersion} (was v${lastSeen}).`,
            'Open stats panel'
        ).then(choice => {
            if (choice === 'Open stats panel') {
                vscode.commands.executeCommand('aiBench.copilotBridge.openStats');
            }
        });
    }
    context.globalState.update(lastSeenKey, runningVersion);

    // Active watcher: when the on-disk packageJSON version diverges
    // from what's running, the user just installed a new VSIX over the
    // top of us. Prompt for an immediate reload so the new code takes
    // effect. Latch the prompt so we only show it once per activation
    // even if VSCode fires onDidChange repeatedly during the install.
    let promptedForReload = false;
    const sub = vscode.extensions.onDidChange(() => {
        if (promptedForReload) return;
        try {
            const ondisk = vscode.extensions.getExtension(ME)?.packageJSON?.version;
            if (ondisk && ondisk !== runningVersion) {
                promptedForReload = true;
                vscode.window.showInformationMessage(
                    `ai-bench Copilot Bridge has a new version installed (v${ondisk}). ` +
                    `Reload the window to switch from the running v${runningVersion}.`,
                    'Reload Window'
                ).then(choice => {
                    if (choice === 'Reload Window') {
                        vscode.commands.executeCommand('workbench.action.reloadWindow');
                    }
                });
            }
        } catch (e) {
            console.error('onDidChange watcher', e);
        }
    });
    context.subscriptions.push(sub);
}

export function activate(context: vscode.ExtensionContext): void {
    extensionContext = context;
    output = vscode.window.createOutputChannel('ai-bench Copilot Bridge');
    context.subscriptions.push(output);
    log('activate: extension starting');
    tracker = new UsageTracker(context);
    // Restore any operator-pushed pricing override (from bench-webui's
    // /admin/pricing editor) so cost estimates stay consistent across
    // VSCode reloads.
    pricingMod.initPricing(context);
    watchForExtensionUpdates(context);

    // Hooks pumped into the WebView so its Start/Stop buttons drive
    // the same lifecycle code paths as the command-palette commands.
    webviewHooks = {
        tracker,
        getRuntimeState,
        startBridge: () => startBridge(context),
        stopBridge,
        startOpenAi: startOpenAiFromConfig,
        stopOpenAi: stopOpenAiAndNotify,
        setAuthToken: (token) => { setAuthToken(token); notifyStateChanged(); },
        generateAuthToken: () => { const t = generateAuthToken(); notifyStateChanged(); return t; },
        getAuthToken,
        setAuthRequired: (required) => { setAuthRequired(required); notifyStateChanged(); },
    };

    statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    statusBar.command = 'aiBench.copilotBridge.openStats';
    refreshStatusBar();
    statusBar.show();
    context.subscriptions.push(statusBar);

    // Persistent left-rail entry — the activity-bar icon (declared in
    // package.json's viewsContainers) opens this tree view, giving the
    // user a permanent landing spot for the extension. The tree mirrors
    // the WebView controls so quick actions are one click away without
    // opening a full editor pane.
    treeProvider = new BridgeTreeProvider(tracker, getRuntimeState);
    context.subscriptions.push(
        vscode.window.registerTreeDataProvider('aiBenchCopilotBridge.controls', treeProvider)
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('aiBench.copilotBridge.start', () => startBridge(context)),
        vscode.commands.registerCommand('aiBench.copilotBridge.stop', () => stopBridge()),
        vscode.commands.registerCommand('aiBench.copilotBridge.openStats',
            () => openStatsPanel(webviewHooks!, context)),
        vscode.commands.registerCommand('aiBench.copilotBridge.clearStats', () => {
            tracker!.clear();
            notifyStateChanged();
            vscode.window.showInformationMessage('Copilot Bridge stats cleared.');
        }),
        vscode.commands.registerCommand('aiBench.copilotBridge.startOpenAiEndpoint', startOpenAiFromConfig),
        vscode.commands.registerCommand('aiBench.copilotBridge.stopOpenAiEndpoint', stopOpenAiAndNotify)
    );

    startBridge(context);

    // Auto-start the local OpenAI endpoint if the user opted in via config.
    const cfg = vscode.workspace.getConfiguration('aiBench.copilotBridge');
    if (cfg.get<boolean>('openAiAutoStart') === true && !isOpenAiServerRunning()) {
        vscode.commands.executeCommand('aiBench.copilotBridge.startOpenAiEndpoint');
    }
}

export function deactivate(): void {
    stopBridge();
    stopOpenAiServer();
}
