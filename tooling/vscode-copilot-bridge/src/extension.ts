import * as vscode from 'vscode';
import * as net from 'net';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

/**
 * ai-bench Copilot Bridge
 *
 * Exposes the `vscode.lm` Language Model API to the ai-bench-java harness
 * over a local Unix socket. The JVM harness speaks JSON-lines:
 *
 *   -> { "id": "uuid", "model": "copilot", "system": "...", "messages": [...] }
 *   <- { "id": "uuid", "content": "...", "modelIdentifier": "...", "promptTokens": n, "completionTokens": m }
 *
 * No creds flow through us — we piggyback on whatever Copilot entitlement
 * the user's signed-in VSCode already has.
 */

let server: net.Server | undefined;

function socketPath(): string {
    const configured = vscode.workspace.getConfiguration('aibench.copilotBridge').get<string>('socketPath');
    if (configured && configured.length > 0) return configured;
    return path.join(os.tmpdir(), 'ai-bench-copilot.sock');
}

function defaultModelFamily(): string {
    return vscode.workspace.getConfiguration('aibench.copilotBridge').get<string>('defaultModelFamily') || 'gpt-4';
}

interface WireRequest {
    id: string;
    model: string;
    system: string;
    messages: { role: 'user' | 'assistant' | 'system'; content: string }[];
    temperature?: number;
    maxTokens?: number;
}

interface WireResponse {
    id: string;
    content?: string;
    modelIdentifier?: string;
    promptTokens?: number;
    completionTokens?: number;
    error?: string;
}

async function handleRequest(req: WireRequest): Promise<WireResponse> {
    try {
        const family = req.model && req.model !== 'copilot' ? req.model : defaultModelFamily();
        const [model] = await vscode.lm.selectChatModels({ family });
        if (!model) {
            return { id: req.id, error: `No chat model available for family: ${family}` };
        }

        const messages: vscode.LanguageModelChatMessage[] = [];
        if (req.system && req.system.length > 0) {
            messages.push(vscode.LanguageModelChatMessage.User(req.system));
        }
        for (const m of req.messages) {
            switch (m.role) {
                case 'user':
                case 'system':
                    messages.push(vscode.LanguageModelChatMessage.User(m.content));
                    break;
                case 'assistant':
                    messages.push(vscode.LanguageModelChatMessage.Assistant(m.content));
                    break;
            }
        }

        const token = new vscode.CancellationTokenSource().token;
        const response = await model.sendRequest(messages, {}, token);
        let content = '';
        for await (const fragment of response.text) {
            content += fragment;
        }
        return {
            id: req.id,
            content,
            modelIdentifier: `${model.vendor}/${model.family}/${model.version}`,
            promptTokens: 0,
            completionTokens: 0,
        };
    } catch (err: any) {
        return { id: req.id, error: err?.message || String(err) };
    }
}

function startServer(context: vscode.ExtensionContext) {
    const sock = socketPath();
    try {
        if (fs.existsSync(sock)) {
            fs.unlinkSync(sock);
        }
    } catch {
        // ignore
    }

    server = net.createServer((socket) => {
        let buffer = '';
        socket.on('data', async (chunk) => {
            buffer += chunk.toString('utf8');
            let nl = buffer.indexOf('\n');
            while (nl !== -1) {
                const line = buffer.slice(0, nl);
                buffer = buffer.slice(nl + 1);
                nl = buffer.indexOf('\n');
                if (!line.trim()) continue;
                try {
                    const req: WireRequest = JSON.parse(line);
                    const resp = await handleRequest(req);
                    socket.write(JSON.stringify(resp) + '\n');
                } catch (e: any) {
                    socket.write(JSON.stringify({ id: 'unknown', error: e?.message || String(e) }) + '\n');
                }
            }
        });
    });

    server.listen(sock, () => {
        vscode.window.showInformationMessage(`ai-bench Copilot bridge listening at ${sock}`);
    });

    context.subscriptions.push({
        dispose: () => {
            server?.close();
            try { fs.unlinkSync(sock); } catch { /* ignore */ }
        },
    });
}

export function activate(context: vscode.ExtensionContext) {
    startServer(context);

    context.subscriptions.push(vscode.commands.registerCommand('aibench.copilotBridge.status', async () => {
        const sock = socketPath();
        const listening = server?.listening ?? false;
        vscode.window.showInformationMessage(`Bridge ${listening ? 'up' : 'down'} · socket ${sock}`);
    }));

    context.subscriptions.push(vscode.commands.registerCommand('aibench.copilotBridge.restart', async () => {
        server?.close();
        startServer(context);
    }));
}

export function deactivate() {
    server?.close();
}
