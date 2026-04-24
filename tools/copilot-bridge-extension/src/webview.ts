// VSCode WebView dashboard for Copilot bridge usage stats. Renders a
// single-page HTML table that re-fetches from the extension every 2s
// while the panel is visible. Also surfaces Start/Stop controls for
// the Unix-socket bridge and the optional local OpenAI endpoint.

import * as vscode from 'vscode';
import { UsageTracker, StatsSnapshot } from './usage';

let panel: vscode.WebviewPanel | undefined;

/**
 * State the WebView needs that lives outside the usage tracker —
 * specifically: whether each transport is currently running. The host
 * extension passes this in via the `getRuntimeState` callback so the
 * UI can update Start/Stop button enabled-state without round-tripping
 * VSCode commands for every refresh.
 */
export interface RuntimeState {
    bridgeRunning: boolean;
    openAiRunning: boolean;
    socketPath: string;
    openAiUrl: string | null;
    auth: {
        tokenPresent: boolean;
        required: boolean;
        bindIsLoopback: boolean;
        tokenPreview: string;
    };
}

export interface WebviewHooks {
    tracker: UsageTracker;
    getRuntimeState: () => RuntimeState;
    startBridge: () => Promise<void> | void;
    stopBridge: () => Promise<void> | void;
    startOpenAi: () => Promise<void> | void;
    stopOpenAi: () => Promise<void> | void;
    /** Replace the current in-memory auth token (null clears it). */
    setAuthToken: (token: string | null) => void;
    /** Generate and install a fresh random Bearer token. Returns it so
     *  the UI can echo it back for the operator to copy. */
    generateAuthToken: () => string;
    /** Return the raw token (callers that display must mask). */
    getAuthToken: () => string | null;
    /** Toggle whether the endpoint enforces Bearer auth. */
    setAuthRequired: (required: boolean) => void;
}

/**
 * Push the latest snapshot + runtime state to an open panel. Called
 * from the host extension whenever (a) a new chat call lands, (b) the
 * bridge state flips, or (c) the OpenAI endpoint is started/stopped.
 */
export function pushUpdate(hooks: WebviewHooks): void {
    if (!panel) return;
    panel.webview.postMessage({
        type: 'snapshot',
        snapshot: hooks.tracker.snapshot(),
        runtime: hooks.getRuntimeState(),
    });
}

export function openStatsPanel(hooks: WebviewHooks, context: vscode.ExtensionContext): void {
    if (panel) {
        panel.reveal(vscode.ViewColumn.Active);
        pushUpdate(hooks);
        return;
    }
    panel = vscode.window.createWebviewPanel(
        'aiBenchCopilotStats',
        'Copilot Bridge — usage',
        vscode.ViewColumn.Active,
        { enableScripts: true, retainContextWhenHidden: false }
    );
    panel.webview.html = renderHtml(hooks.tracker.snapshot(), hooks.getRuntimeState());

    const sub = hooks.tracker.onChange(() => pushUpdate(hooks));
    panel.webview.onDidReceiveMessage(async msg => {
        try {
            switch (msg?.type) {
                case 'clear':               hooks.tracker.clear(); break;
                case 'startBridge':         await hooks.startBridge(); break;
                case 'stopBridge':          await hooks.stopBridge(); break;
                case 'startOpenAi':         await hooks.startOpenAi(); break;
                case 'stopOpenAi':          await hooks.stopOpenAi(); break;
                case 'setAuthToken':        hooks.setAuthToken(msg.token ?? null); break;
                case 'clearAuthToken':      hooks.setAuthToken(null); break;
                case 'generateAuthToken':
                    // Mint silently — the new token is loaded into the
                    // input field but kept masked. Operator must click
                    // Show or Copy to access it.
                    panel!.webview.postMessage({
                        type: 'authToken',
                        token: hooks.generateAuthToken(),
                        reveal: false,
                    });
                    break;
                case 'revealAuthToken':
                    // Explicit Show click — reveal the cleartext on
                    // the webview postMessage channel only, not via
                    // the global snapshot stream.
                    panel!.webview.postMessage({
                        type: 'authToken',
                        token: hooks.getAuthToken(),
                        reveal: true,
                    });
                    break;
                case 'setAuthRequired':     hooks.setAuthRequired(!!msg.required); break;
            }
        } catch (e: any) {
            vscode.window.showErrorMessage(`Bridge action '${msg?.type}' failed: ${e?.message ?? e}`);
        }
        // Always push a fresh snapshot — even on action failure — so
        // the WebView's optimistic-disable is cleared and the controls
        // reflect the real current state.
        pushUpdate(hooks);
    });
    panel.onDidDispose(() => { sub.dispose(); panel = undefined; });
    context.subscriptions.push(sub);
}

function renderHtml(initial: StatsSnapshot, runtime: RuntimeState): string {
    const initialPayload = JSON.stringify({ snapshot: initial, runtime })
        .replace(/</g, '\\u003c');
    return `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8" />
<style>
    body { font-family: -apple-system, "system-ui", sans-serif; padding: 1em; color: var(--vscode-foreground); }
    h1 { font-size: 1.2em; margin-bottom: 0.4em; }
    h2 { font-size: 1em; margin-top: 1.2em; }
    .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 0.5em; margin: 0.5em 0; }
    .card { background: var(--vscode-editor-inactiveSelectionBackground); padding: 0.6em; border-radius: 4px; }
    .card .label { font-size: 0.8em; opacity: 0.7; }
    .card .value { font-size: 1.4em; font-weight: 600; margin-top: 0.2em; }
    .controls { background: var(--vscode-editor-inactiveSelectionBackground); padding: 0.7em; border-radius: 4px; margin: 0.5em 0; }
    .controls .row { display: flex; align-items: center; gap: 0.6em; margin: 0.3em 0; flex-wrap: wrap; }
    .controls .row strong { min-width: 12em; }
    .panel { background: var(--vscode-editor-inactiveSelectionBackground); padding: 0.8em 0.9em; border-radius: 6px; margin: 0.6em 0; border-left: 3px solid var(--vscode-focusBorder, #007acc); }
    .panel h3 { margin: 0 0 0.4em 0; font-size: 1em; }
    .panel .row { display: flex; align-items: center; gap: 0.6em; margin: 0.3em 0; flex-wrap: wrap; }
    .panel .row strong { min-width: 8em; }
    .url-pill { display: inline-block; padding: 0.25em 0.7em; border-radius: 4px; background: rgba(255,255,255,0.06); font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace; }
    .badge { display: inline-block; padding: 0.1em 0.5em; border-radius: 3px; font-size: 0.85em; }
    .badge.on  { background: #2c6d3a; color: #d6f3dd; }
    .badge.off { background: #6b2a2a; color: #f3d6d6; }
    .target { font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace; opacity: 0.85; font-size: 0.9em; }
    table { border-collapse: collapse; width: 100%; margin-top: 0.3em; font-size: 0.9em; }
    th, td { border-bottom: 1px solid var(--vscode-panel-border); padding: 0.3em 0.5em; text-align: left; }
    th { font-weight: 600; }
    td.num, th.num { text-align: right; }
    .toolbar { margin-top: 0.6em; }
    button { background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: none; padding: 0.35em 0.9em; border-radius: 3px; cursor: pointer; font-size: 0.9em; }
    button:disabled { opacity: 0.45; cursor: not-allowed; }
    button.danger { background: #b91c1c; color: white; }
    .help { font-size: 0.85em; opacity: 0.7; margin-top: 0.4em; }
</style>
</head>
<body>
<h1>ai-bench Copilot Bridge — usage</h1>

<!-- Local IPC bridge — distinct panel, blue accent -->
<div class="panel" style="border-left-color: #4a9be8">
    <h3>① Local IPC bridge</h3>
    <div class="row">
        <strong>Status</strong>
        <span id="bridgeBadge" class="badge off">stopped</span>
        <span id="bridgeTarget" class="target"></span>
        <button id="bridgeToggle"></button>
    </div>
    <p class="help" style="margin:0.4em 0 0 0">
        The socket bridge provides client transport to GitHub Copilot
        Models for the ai-bench harness. AF_UNIX socket — native on
        macOS / Linux; on Windows 10 build 17063 (version 1803) and
        later. Default path on Windows lives under <code>%TEMP%</code>.
    </p>
</div>

<!-- Local OpenAI endpoint — distinct panel, green accent -->
<div class="panel" style="border-left-color: #4ec77f">
    <h3>② Local OpenAI-compatible endpoint</h3>
    <div class="row">
        <strong>Status</strong>
        <span id="openAiBadge" class="badge off">stopped</span>
        <button id="openAiToggle"></button>
    </div>
    <div class="row" id="openAiUrlRow" style="display:none">
        <strong>URL</strong>
        <span id="openAiUrl" class="url-pill"></span>
        <button id="openAiCopyUrlBtn">Copy URL</button>
    </div>
    <div class="row" style="flex-wrap:wrap; align-items:flex-start">
        <strong>Auth</strong>
        <span id="authStateBadge" class="badge off">—</span>
        <label style="display:flex; align-items:center; gap:0.3em">
            <input type="checkbox" id="authRequiredBox">
            Require Bearer token
        </label>
        <span id="authLoopbackHint" class="help" style="margin-left:0.4em"></span>
    </div>
    <div class="row" id="authTokenRow" style="flex-wrap:wrap">
        <strong>Token</strong>
        <input type="password" id="authTokenInput"
               style="flex:1 1 22em; min-width:0;
                      font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace"
               placeholder="(no token set — Generate to mint one)"
               autocomplete="off" readonly>
        <button id="authGenerateBtn">Generate new</button>
        <button id="authRevealBtn">Show</button>
        <button id="authCopyBtn">Copy</button>
        <button id="authClearBtn" class="danger">Clear</button>
    </div>
    <p class="help" style="margin:0.4em 0 0 0">
        Exposes Copilot under the standard
        <code>/v1/chat/completions</code> + <code>/v1/models</code>
        wire so curl, the openai SDK, LangChain, Ollama-mode tools,
        etc. can use Copilot. Bearer-token auth is OFF by default
        because the endpoint binds to loopback; flip the toggle to
        require a token if you change the bind address.
    </p>
</div>

<p class="help">
    Token counts are character-based approximations (~4 chars/token).
    Cost estimates use the embedded public-pricing table.
</p>

<div id="totals" class="cards"></div>

<h2>Per model</h2>
<table id="perModel"><thead><tr>
    <th>Model</th><th class="num">Requests</th><th class="num">Prompt tok</th><th class="num">Compl. tok</th><th class="num">Est. cost (USD)</th>
</tr></thead><tbody></tbody></table>

<h2>Top consumers</h2>
<p class="help">Clients are identified by the <code>client</code> field on socket calls or the <code>X-Client-Id</code> header on HTTP calls. Default = "anonymous".</p>
<table id="perClient"><thead><tr>
    <th>Client</th><th class="num">Requests</th><th class="num">Prompt tok</th><th class="num">Compl. tok</th><th class="num">Est. cost (USD)</th><th>Last seen</th>
</tr></thead><tbody></tbody></table>

<h2>Recent activity</h2>
<table id="recent"><thead><tr>
    <th>When</th><th>Model</th><th>Client</th><th>Via</th><th class="num">Prompt</th><th class="num">Compl.</th><th class="num">Cost</th>
</tr></thead><tbody></tbody></table>

<div class="toolbar">
    <button id="clearBtn" class="danger">Clear all stats</button>
</div>

<script>
const vscode = acquireVsCodeApi();
let state = ${initialPayload};
function fmt(n){ return Number(n).toLocaleString(); }
function fmtUsd(n){ return '$' + Number(n).toFixed(6); }
function fmtTime(iso){ return new Date(iso).toLocaleTimeString(); }

// Safety belt: every click optimistically disables its button so the
// user can't double-fire an action while the extension is processing
// it. The extension is supposed to push a fresh snapshot back which
// will re-enable the button via renderControls. If for any reason
// that snapshot never arrives, this timeout re-enables anyway so the
// UI never gets stuck — fallback covers race conditions / missed
// messages / bridge crashes.
function armSafetyReenable(button) {
    setTimeout(function(){
        if (button) button.disabled = false;
    }, 3000);
}

function renderControls() {
    try {
        var rt = state.runtime || {};
        // OpenAI URL display
        var urlRow = document.getElementById('openAiUrlRow');
        var urlEl = document.getElementById('openAiUrl');
        if (urlRow && urlEl) {
            if (rt.openAiUrl) {
                urlRow.style.display = 'flex';
                urlEl.textContent = rt.openAiUrl;
            } else {
                urlRow.style.display = 'none';
                urlEl.textContent = '';
            }
        }
        // Auth section ------------------------------------------------
        var auth = rt.auth || { tokenPresent:false, required:false, bindIsLoopback:true, tokenPreview:'' };
        var tokenRow = document.getElementById('authTokenRow');
        if (tokenRow) tokenRow.style.display = auth.required ? 'flex' : 'none';
        var authBadge = document.getElementById('authStateBadge');
        var requireBox = document.getElementById('authRequiredBox');
        var loopHint = document.getElementById('authLoopbackHint');
        if (authBadge) {
            if (auth.required) {
                authBadge.className = 'badge ' + (auth.tokenPresent ? 'on' : 'off');
                authBadge.textContent = auth.tokenPresent
                    ? 'Bearer required · token: ' + (auth.tokenPreview || '(set)')
                    : 'Bearer required · NO TOKEN';
            } else if (auth.bindIsLoopback) {
                authBadge.className = 'badge on';
                authBadge.textContent = 'Anonymous (loopback only)';
            } else {
                authBadge.className = 'badge off';
                authBadge.textContent = 'Disabled but bound off-loopback — requests refused';
            }
        }
        if (requireBox) requireBox.checked = !!auth.required;
        if (loopHint) {
            loopHint.textContent = auth.bindIsLoopback
                ? '(loopback bind — anonymous mode is safe)'
                : '(non-loopback bind — leave auth required)';
        }
        var bb = document.getElementById('bridgeBadge');
        bb.textContent = rt.bridgeRunning ? 'running' : 'stopped';
        bb.className = 'badge ' + (rt.bridgeRunning ? 'on' : 'off');
        document.getElementById('bridgeTarget').textContent = rt.socketPath || '';
        var bt = document.getElementById('bridgeToggle');
        bt.disabled = false;
        bt.textContent = rt.bridgeRunning ? 'Stop bridge' : 'Start bridge';
        if (rt.bridgeRunning) bt.classList.add('danger'); else bt.classList.remove('danger');
        // Capture the running flag at render time — the click handler
        // closes over the local copy so a refresh-after-click that
        // mutates rt cannot send the wrong action.
        var bridgeRunningSnapshot = !!rt.bridgeRunning;
        bt.onclick = function(){
            bt.disabled = true;
            armSafetyReenable(bt);
            vscode.postMessage({ type: bridgeRunningSnapshot ? 'stopBridge' : 'startBridge' });
        };

        var ob = document.getElementById('openAiBadge');
        if (ob) {
            ob.textContent = rt.openAiRunning ? 'running' : 'stopped';
            ob.className = 'badge ' + (rt.openAiRunning ? 'on' : 'off');
        }
        // openAiTarget was removed when the URL got its own dedicated
        // row above (#openAiUrl). Don't reference it here — a TypeError
        // here would silently abort renderControls before the toggle
        // button below gets wired up.
        var ot = document.getElementById('openAiToggle');
        ot.disabled = false;
        ot.textContent = rt.openAiRunning ? 'Stop endpoint' : 'Start endpoint';
        if (rt.openAiRunning) ot.classList.add('danger'); else ot.classList.remove('danger');
        var openAiRunningSnapshot = !!rt.openAiRunning;
        ot.onclick = function(){
            ot.disabled = true;
            armSafetyReenable(ot);
            vscode.postMessage({ type: openAiRunningSnapshot ? 'stopOpenAi' : 'startOpenAi' });
        };
    } catch (e) {
        // Surface in the developer console AND directly in the panel
        // so silent failures of this kind can never hide a missing
        // button again.
        console.error('renderControls failed:', e);
        try {
            var holder = document.body;
            if (holder) {
                var div = document.createElement('div');
                div.style.cssText = 'background:#b91c1c;color:white;padding:0.5em;border-radius:4px;margin:0.5em 0;font-size:0.85em';
                div.textContent = 'Internal renderControls error: ' + (e && e.message ? e.message : String(e));
                holder.insertBefore(div, holder.firstChild);
            }
        } catch (_e) { /* nothing more we can do */ }
    }
}

function renderStats() {
    var snap = state.snapshot || { totalRequests:0, totalPromptTokens:0, totalCompletionTokens:0, totalEstimatedCostUsd:0, perModel:[], perClient:[], recent:[] };
    document.getElementById('totals').innerHTML =
      card('Requests', fmt(snap.totalRequests)) +
      card('Prompt tokens', fmt(snap.totalPromptTokens)) +
      card('Completion tokens', fmt(snap.totalCompletionTokens)) +
      card('Est. cost (USD)', fmtUsd(snap.totalEstimatedCostUsd));
    fillTable('perModel', snap.perModel.map(function(m){ return [
      m.label || m.modelId, fmt(m.requests), fmt(m.promptTokens), fmt(m.completionTokens), fmtUsd(m.estimatedCostUsd)
    ]; }));
    fillTable('perClient', snap.perClient.map(function(c){ return [
      c.client, fmt(c.requests), fmt(c.promptTokens), fmt(c.completionTokens), fmtUsd(c.estimatedCostUsd), c.lastSeenIso ? new Date(c.lastSeenIso).toLocaleString() : ''
    ]; }));
    fillTable('recent', snap.recent.map(function(r){ return [
      fmtTime(r.whenIso), r.modelId, r.client, r.via, fmt(r.promptTokens), fmt(r.completionTokens), fmtUsd(r.estimatedCostUsd)
    ]; }));
}

function card(label, value){
    return '<div class="card"><div class="label">'+label+'</div><div class="value">'+value+'</div></div>';
}
function fillTable(id, rows){
    var tbody = document.querySelector('#'+id+' tbody');
    tbody.innerHTML = rows.map(function(r){
        return '<tr>' + r.map(function(c, i){
            return '<td' + (i > 0 ? ' class="num"' : '') + '>' + (c == null ? '' : c) + '</td>';
        }).join('') + '</tr>';
    }).join('');
}

window.addEventListener('message', function(ev){
    if (!ev.data) return;
    if (ev.data.type === 'snapshot') {
        state.snapshot = ev.data.snapshot;
        if (ev.data.runtime) state.runtime = ev.data.runtime;
        renderControls(); renderStats();
    } else if (ev.data.type === 'authToken') {
        // The extension loads the token into the field. Whether to
        // reveal or keep masked is controlled by the explicit reveal
        // flag set on the message — Generate keeps it masked, Show
        // flips it to plaintext.
        var inp = document.getElementById('authTokenInput');
        if (inp) {
            inp.value = ev.data.token || '';
            if (ev.data.reveal) {
                showToken();
            } else {
                hideToken();
                // Subtle confirmation flash so the user knows the
                // generated token landed in the field.
                inp.style.outline = '2px solid #4ec77f';
                setTimeout(function(){ inp.style.outline = ''; }, 600);
            }
        }
    }
});
document.getElementById('clearBtn').onclick = function(){ vscode.postMessage({ type: 'clear' }); };
// Copy-URL button for the OpenAI endpoint.
var copyUrlBtn = document.getElementById('openAiCopyUrlBtn');
if (copyUrlBtn) copyUrlBtn.onclick = function(){
    var u = document.getElementById('openAiUrl').textContent || '';
    if (u) navigator.clipboard.writeText(u);
};

// --- Auth controls --------------------------------------------------
// Token-reveal lifecycle. Show toggles to Hide while the token is
// visible AND auto-hides after 15 seconds. Both transitions go
// through showToken() / hideToken() so the button label and the
// pending timer can never get out of sync.

var REVEAL_TIMEOUT_MS = 15000;
var revealTimerId = null;

function setRevealButtonLabel(showing) {
    var btn = document.getElementById('authRevealBtn');
    if (!btn) return;
    btn.textContent = showing ? 'Hide' : 'Show';
    if (showing) btn.classList.add('danger'); else btn.classList.remove('danger');
}

function clearRevealTimer() {
    if (revealTimerId !== null) { clearTimeout(revealTimerId); revealTimerId = null; }
}

function showToken() {
    var inp = document.getElementById('authTokenInput');
    if (!inp) return;
    inp.type = 'text';
    inp.focus();
    inp.select();
    setRevealButtonLabel(true);
    clearRevealTimer();
    revealTimerId = setTimeout(function(){
        // Re-mask on the timer; honor the user's choice if they
        // already hit Hide / Clear in the interim.
        if (inp.type === 'text') hideToken();
    }, REVEAL_TIMEOUT_MS);
}

function hideToken() {
    var inp = document.getElementById('authTokenInput');
    if (!inp) return;
    inp.type = 'password';
    setRevealButtonLabel(false);
    clearRevealTimer();
}

function wireAuthControls() {
    var requireBox = document.getElementById('authRequiredBox');
    var input = document.getElementById('authTokenInput');
    requireBox.onchange = function(){
        vscode.postMessage({ type: 'setAuthRequired', required: requireBox.checked });
    };
    document.getElementById('authGenerateBtn').onclick = function(){
        vscode.postMessage({ type: 'generateAuthToken' });
    };
    document.getElementById('authRevealBtn').onclick = function(){
        // Toggle: if currently showing, hide right away; otherwise
        // ask the extension for the cleartext and then call
        // showToken() in the message handler.
        if (input.type === 'text') {
            hideToken();
        } else {
            vscode.postMessage({ type: 'revealAuthToken' });
        }
    };
    document.getElementById('authCopyBtn').onclick = function(){
        // Copies whatever is currently in the input box (which is
        // populated by the previous Show / Generate click).
        if (input.value) {
            navigator.clipboard.writeText(input.value).then(function(){
                input.style.outline = '2px solid #2c6d3a';
                setTimeout(function(){ input.style.outline = ''; }, 600);
            });
        }
    };
    document.getElementById('authClearBtn').onclick = function(){
        input.value = '';
        hideToken();
        vscode.postMessage({ type: 'clearAuthToken' });
    };
    setRevealButtonLabel(false);
}
wireAuthControls();
renderControls(); renderStats();
</script>
</body>
</html>`;
}
