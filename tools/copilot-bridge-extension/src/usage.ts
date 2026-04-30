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
    /**
     * Truncated preview of the request prompt + the model's completion,
     * for the webview's click-to-expand "inspect this call" panel.
     * Capped at PREVIEW_CHARS each so a single bursty conversation
     * can't blow out globalState — for full traffic capture, the
     * harness already saves AppMap traces / Navie transcripts.
     */
    promptPreview?: string;
    completionPreview?: string;
    /**
     * Caller-supplied correlation id. Lets the test harness tag every
     * bridge call made on behalf of a specific BenchmarkRun (id like
     * "run-abc123") so the webview can filter the activity to one run
     * AND so the harness can later query the bridge for authoritative
     * token totals scoped to that run -- bypassing the synthetic
     * counts BenchmarkRunService.simulate() generates locally.
     * Surfaced via:
     *   * socket op:  request JSON's `runId` field
     *   * HTTP shim:  X-Run-Id request header
     */
    runId?: string;
}

/** Max chars retained per side for the click-to-expand preview. */
const PREVIEW_CHARS = 1500;
function truncatePreview(s: string): string {
    if (!s) return '';
    if (s.length <= PREVIEW_CHARS) return s;
    return s.slice(0, PREVIEW_CHARS) + `\n… [truncated; ${s.length - PREVIEW_CHARS} more chars]`;
}

/**
 * In-memory full-body store, keyed by record ts. We deliberately do NOT
 * persist these — globalState is bounded and full prompts can hit
 * megabytes per record. They survive while the VSCode window is open;
 * on restart only the truncated preview remains. The webview's
 * "View full prompt" button + the /v1/activity/full?ts=... HTTP route
 * both serve from this map; both fall back gracefully (showing only
 * the preview) when the entry has aged out.
 */
interface FullBody { promptFull: string; completionFull: string; ts: number; }
const FULL_BODY_LIMIT = 200; // capped so a leak can't grow unbounded

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

export interface RecentEntry {
    whenIso: string;
    modelId: string;
    client: string;
    promptTokens: number;
    completionTokens: number;
    estimatedCostUsd: number;
    via: string;
    /** Truncated request prompt for the click-to-expand panel. */
    promptPreview?: string;
    /** Truncated model response for the click-to-expand panel. */
    completionPreview?: string;
    /** Caller-supplied correlation id (e.g. BenchmarkRun.id). */
    runId?: string;
}

/**
 * Per-run aggregate. Returned by snapshotForRunId() so the harness can
 * query "how many tokens / how much cost did THIS run consume?" without
 * having to walk every record itself.
 */
export interface RunIdSnapshot {
    runId: string;
    requests: number;
    promptTokens: number;
    completionTokens: number;
    estimatedCostUsd: number;
    firstSeenIso: string | null;
    lastSeenIso: string | null;
    perModel: ModelTotals[];
    /** Latest 50 entries for this runId, newest first. */
    recent: RecentEntry[];
}

export interface StatsSnapshot {
    sinceIso: string;
    totalRequests: number;
    totalPromptTokens: number;
    totalCompletionTokens: number;
    totalEstimatedCostUsd: number;
    perModel: ModelTotals[];
    perClient: ClientTotals[];
    /**
     * Last RECENT_LIMIT entries. The webview paginates this client-side
     * (10 / 25 / 50). We send the full RECENT_LIMIT so a switch to a
     * larger page size doesn't have to round-trip back here.
     */
    recent: RecentEntry[];
}

const STATE_KEY = 'aiBenchCopilotBridge.usage.records';
/**
 * Ring-buffer cap. Keeping this modest (1000) caps globalState size:
 * with PREVIEW_CHARS per side, a record is ~3.5 KB worst-case, so
 * MAX_RECORDS=1000 -> ~3.5 MB persisted. Stats roll up over the full
 * buffer; the recent-activity view only ever needs the tail.
 */
const MAX_RECORDS = 1000;
/** Number of recent entries the snapshot returns to the webview. */
const RECENT_LIMIT = 50;

export class UsageTracker {
    private records: RawRecord[];
    private listeners: Array<() => void> = [];
    /** Volatile full-body store; see FullBody comment above. */
    private fullBodies: Map<number, FullBody> = new Map();

    constructor(private readonly context: vscode.ExtensionContext) {
        this.records = (context.globalState.get<RawRecord[]>(STATE_KEY) ?? []).slice(-MAX_RECORDS);
    }

    /** Return the full prompt + completion for a recorded ts, if still
     *  in the in-memory store. Used by the webview "View full prompt"
     *  button and the HTTP /v1/activity/full?ts=... route. */
    getFullBody(ts: number): FullBody | undefined {
        return this.fullBodies.get(ts);
    }
    /** Snapshot of every retained full body (newest first). Used by
     *  Export and by /v1/activity/full when no ts is given. */
    allFullBodies(): FullBody[] {
        return Array.from(this.fullBodies.values()).sort((a, b) => b.ts - a.ts);
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
        /** Optional caller-supplied correlation id (BenchmarkRun.id, span id, etc). */
        runId?: string;
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
            promptPreview: truncatePreview(args.promptText),
            completionPreview: truncatePreview(args.completionText),
            runId: args.runId && args.runId.trim().length > 0 ? args.runId.trim() : undefined,
        };
        this.records.push(rec);
        if (this.records.length > MAX_RECORDS) {
            this.records.splice(0, this.records.length - MAX_RECORDS);
        }
        // Stash the full bodies in-memory only — see FullBody comment.
        this.fullBodies.set(rec.ts,
            { ts: rec.ts, promptFull: args.promptText, completionFull: args.completionText });
        if (this.fullBodies.size > FULL_BODY_LIMIT) {
            // Evict the oldest until we're under cap. Maps keep insertion
            // order so the first key is the oldest.
            const it = this.fullBodies.keys();
            while (this.fullBodies.size > FULL_BODY_LIMIT) {
                const k = it.next().value;
                if (k === undefined) break;
                this.fullBodies.delete(k);
            }
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
        const recent: RecentEntry[] = this.records.slice(-RECENT_LIMIT).reverse().map(r => ({
            whenIso: new Date(r.ts).toISOString(),
            modelId: r.modelId,
            client: r.client,
            promptTokens: r.promptTokens,
            completionTokens: r.completionTokens,
            estimatedCostUsd: r.estimatedCostUsd,
            via: r.via,
            promptPreview: r.promptPreview ?? '',
            completionPreview: r.completionPreview ?? '',
            runId: r.runId,
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

    /**
     * Distinct runIds present in the current records, newest-seen first.
     * Used by the webview's "filter by run" dropdown.
     */
    knownRunIds(): string[] {
        const seen = new Map<string, number>(); // runId -> latest ts
        for (const r of this.records) {
            if (!r.runId) continue;
            const prev = seen.get(r.runId);
            if (prev === undefined || r.ts > prev) seen.set(r.runId, r.ts);
        }
        return Array.from(seen.entries())
            .sort((a, b) => b[1] - a[1])
            .map(e => e[0]);
    }

    /**
     * Per-runId snapshot. Returned by GET /v1/activity?runId= so the
     * harness can ask the bridge for AUTHORITATIVE token totals (rather
     * than relying on locally-computed approximations from the
     * synthetic simulate worker). Returns null when no records carry
     * that runId.
     */
    snapshotForRunId(runId: string): RunIdSnapshot | null {
        const matched = this.records.filter(r => r.runId === runId);
        if (matched.length === 0) return null;

        let totalPrompt = 0, totalCompletion = 0, totalCost = 0;
        let firstTs = matched[0].ts, lastTs = matched[0].ts;
        const perModelMap = new Map<string, ModelTotals>();
        for (const r of matched) {
            totalPrompt += r.promptTokens;
            totalCompletion += r.completionTokens;
            totalCost += r.estimatedCostUsd;
            if (r.ts < firstTs) firstTs = r.ts;
            if (r.ts > lastTs) lastTs = r.ts;
            const m = perModelMap.get(r.modelId) ?? {
                modelId: r.modelId,
                label: priceFor(r.modelId)?.label ?? r.modelId,
                requests: 0, promptTokens: 0, completionTokens: 0, estimatedCostUsd: 0,
            };
            m.requests++;
            m.promptTokens += r.promptTokens;
            m.completionTokens += r.completionTokens;
            m.estimatedCostUsd += r.estimatedCostUsd;
            perModelMap.set(r.modelId, m);
        }
        const recent: RecentEntry[] = matched
            .slice(-RECENT_LIMIT).reverse()
            .map(r => ({
                whenIso: new Date(r.ts).toISOString(),
                modelId: r.modelId,
                client: r.client,
                promptTokens: r.promptTokens,
                completionTokens: r.completionTokens,
                estimatedCostUsd: r.estimatedCostUsd,
                via: r.via,
                promptPreview: r.promptPreview ?? '',
                completionPreview: r.completionPreview ?? '',
                runId: r.runId,
            }));
        return {
            runId,
            requests: matched.length,
            promptTokens: totalPrompt,
            completionTokens: totalCompletion,
            estimatedCostUsd: totalCost,
            firstSeenIso: new Date(firstTs).toISOString(),
            lastSeenIso: new Date(lastTs).toISOString(),
            perModel: [...perModelMap.values()].sort((a, b) => b.estimatedCostUsd - a.estimatedCostUsd),
            recent,
        };
    }

    clear(): void {
        this.records = [];
        this.fullBodies.clear();
        this.context.globalState.update(STATE_KEY, this.records);
        this.fireListeners();
    }
}
