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
    return all.find(m => m.id === req.model) ?? all[0];
}

async function handleSocketRequest(raw: string, socket: net.Socket): Promise<void> {
    let req: any;
    try { req = JSON.parse(raw); } catch (e: any) {
        socket.write(JSON.stringify({ ok: false, error: 'invalid JSON: ' + e.message }) + '\n');
        return;
    }
    try {
        const op = req.op ?? 'chat';
        const client = String(req.client ?? socket.remoteAddress ?? 'socket-anonymous');
        switch (op) {
            case 'list-models': {
                const models = await enumerateModels();
                socket.write(JSON.stringify({
                    ok: true, models, auto: models[0]?.id ?? null, count: models.length
                }) + '\n');
                return;
            }
            case 'chat': {
                const model = await pickModel(req);
                if (!model) {
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

                const tok = new vscode.CancellationTokenSource().token;
                const resp = await model.sendRequest(inputMessages, {}, tok);
                let content = '';
                for await (const frag of resp.text) content += frag;

                tracker?.record({
                    modelId: model.id, client, promptText, completionText: content, via: 'socket'
                });
                treeProvider?.refresh();
                refreshStatusBar();

                socket.write(JSON.stringify({
                    ok: true, content, modelId: model.id, modelName: model.name
                }) + '\n');
                return;
            }
            default:
                socket.write(JSON.stringify({ ok: false,
                    error: `unknown op '${op}' — expected 'chat' or 'list-models'` }) + '\n');
        }
    } catch (e: any) {
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
