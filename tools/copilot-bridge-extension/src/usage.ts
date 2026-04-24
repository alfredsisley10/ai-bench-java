// Persistent per-model / per-client usage accounting for the Copilot
// bridge. Counts are kept in extension globalState so they survive
// VSCode restarts; reset via the "ai-bench: Clear Usage Stats" command.

import * as vscode from 'vscode';
import { approxTokens, estimateCost, priceFor } from './pricing';

interface RawRecord {
    ts: number;
    modelId: string;
    client: string;
    promptTokens: number;
    completionTokens: number;
    estimatedCostUsd: number;
    via: 'socket' | 'openai-http';
}

export interface ModelTotals {
    modelId: string;
    label: string;
    requests: number;
    promptTokens: number;
    completionTokens: number;
    estimatedCostUsd: number;
}

export interface ClientTotals {
    client: string;
    requests: number;
    promptTokens: number;
    completionTokens: number;
    estimatedCostUsd: number;
    lastSeenIso: string;
}

export interface StatsSnapshot {
    sinceIso: string;
    totalRequests: number;
    totalPromptTokens: number;
    totalCompletionTokens: number;
    totalEstimatedCostUsd: number;
    perModel: ModelTotals[];
    perClient: ClientTotals[];
    recent: Array<{
        whenIso: string;
        modelId: string;
        client: string;
        promptTokens: number;
        completionTokens: number;
        estimatedCostUsd: number;
        via: string;
    }>;
}

const STATE_KEY = 'aiBenchCopilotBridge.usage.records';
const MAX_RECORDS = 5000;   // ring-buffer cap to keep globalState bounded

export class UsageTracker {
    private records: RawRecord[];
    private listeners: Array<() => void> = [];

    constructor(private readonly context: vscode.ExtensionContext) {
        this.records = (context.globalState.get<RawRecord[]>(STATE_KEY) ?? []).slice(-MAX_RECORDS);
    }

    /**
     * Record one chat-completion call, computing prompt/output token
     * estimates from the raw text since VSCode's LanguageModelChat API
     * doesn't currently expose usage counters. We log the approximation
     * source so the UI can display a "approximate counts" disclaimer.
     */
    private firingListeners = false;

    record(args: {
        modelId: string;
        client: string;
        promptText: string;
        completionText: string;
        via: 'socket' | 'openai-http';
    }): void {
        const promptTokens = approxTokens(args.promptText);
        const completionTokens = approxTokens(args.completionText);
        const estimatedCostUsd = estimateCost(args.modelId, promptTokens, completionTokens);
        const rec: RawRecord = {
            ts: Date.now(),
            modelId: args.modelId,
            client: args.client || 'anonymous',
            promptTokens, completionTokens, estimatedCostUsd,
            via: args.via,
        };
        this.records.push(rec);
        if (this.records.length > MAX_RECORDS) {
            this.records.splice(0, this.records.length - MAX_RECORDS);
        }
        // Persist async — don't block the chat path.
        this.context.globalState.update(STATE_KEY, this.records);
        this.fireListeners();
    }

    /**
     * Snapshot the listener array before iterating + guard against
     * re-entry. A listener that records (or clears) inside its own
     * handler would otherwise trigger infinite recursion through
     * fireListeners → handler → record/clear → fireListeners.
     */
    private fireListeners(): void {
        if (this.firingListeners) return;
        this.firingListeners = true;
        try {
            const snapshot = this.listeners.slice();
            for (const fn of snapshot) {
                try { fn(); } catch (e) { console.error('UsageTracker listener threw', e); }
            }
        } finally {
            this.firingListeners = false;
        }
    }

    onChange(fn: () => void): vscode.Disposable {
        this.listeners.push(fn);
        return new vscode.Disposable(() => {
            this.listeners = this.listeners.filter(f => f !== fn);
        });
    }

    snapshot(): StatsSnapshot {
        const sinceIso = this.records[0]
            ? new Date(this.records[0].ts).toISOString()
            : new Date().toISOString();

        const perModelMap = new Map<string, ModelTotals>();
        const perClientMap = new Map<string, ClientTotals>();
        let totalPrompt = 0, totalCompletion = 0, totalCost = 0;

        for (const r of this.records) {
            totalPrompt += r.promptTokens;
            totalCompletion += r.completionTokens;
            totalCost += r.estimatedCostUsd;

            const mEntry = perModelMap.get(r.modelId) ?? {
                modelId: r.modelId,
                label: priceFor(r.modelId)?.label ?? r.modelId,
                requests: 0, promptTokens: 0, completionTokens: 0, estimatedCostUsd: 0,
            };
            mEntry.requests++;
            mEntry.promptTokens += r.promptTokens;
            mEntry.completionTokens += r.completionTokens;
            mEntry.estimatedCostUsd += r.estimatedCostUsd;
            perModelMap.set(r.modelId, mEntry);

            const cEntry = perClientMap.get(r.client) ?? {
                client: r.client,
                requests: 0, promptTokens: 0, completionTokens: 0, estimatedCostUsd: 0,
                lastSeenIso: '',
            };
            cEntry.requests++;
            cEntry.promptTokens += r.promptTokens;
            cEntry.completionTokens += r.completionTokens;
            cEntry.estimatedCostUsd += r.estimatedCostUsd;
            const ts = new Date(r.ts).toISOString();
            if (ts > cEntry.lastSeenIso) cEntry.lastSeenIso = ts;
            perClientMap.set(r.client, cEntry);
        }

        const perModel = [...perModelMap.values()]
            .sort((a, b) => b.estimatedCostUsd - a.estimatedCostUsd);
        const perClient = [...perClientMap.values()]
            .sort((a, b) => b.requests - a.requests);
        const recent = this.records.slice(-25).reverse().map(r => ({
            whenIso: new Date(r.ts).toISOString(),
            modelId: r.modelId,
            client: r.client,
            promptTokens: r.promptTokens,
            completionTokens: r.completionTokens,
            estimatedCostUsd: r.estimatedCostUsd,
            via: r.via,
        }));

        return {
            sinceIso,
            totalRequests: this.records.length,
            totalPromptTokens: totalPrompt,
            totalCompletionTokens: totalCompletion,
            totalEstimatedCostUsd: totalCost,
            perModel, perClient, recent,
        };
    }

    clear(): void {
        this.records = [];
        this.context.globalState.update(STATE_KEY, this.records);
        this.fireListeners();
    }
}
