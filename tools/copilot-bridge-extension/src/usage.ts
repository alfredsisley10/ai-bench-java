// Persistent per-model / per-client usage accounting for the Copilot
// bridge. Counts are kept in extension globalState so they survive
// VSCode restarts; reset via the "ai-bench: Clear Usage Stats" command.

import * as vscode from 'vscode';
import { approxTokens, estimateCost, priceFor } from './pricing';

/** Lifecycle status for a bridge call. */
export type CallStatus = 'pending' | 'success' | 'failed';

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
    /**
     * Per-request lifecycle id assigned by the OpenAI shim / socket
     * dispatcher. Stable for the duration of one bridge call — used
     * by markPending → markSuccess / markFailed to mutate the same
     * record in place rather than appending a second one.
     */
    reqId?: string;
    /**
     * 'pending' = received by the bridge, not yet fulfilled by Copilot.
     * 'success' = response streamed back to the caller end-to-end.
     * 'failed'  = the bridge call threw OR Copilot returned an error.
     * Pre-existing records (logged before the upgrade) have no status
     * and read as success at the UI level.
     */
    status?: CallStatus;
    /** Error message captured when status='failed'. */
    errorMessage?: string;
    /** Wall-clock ms when status transitioned out of 'pending' (success
     *  OR failed). Combined with [ts] (the markPending timestamp) gives
     *  the call's actual completion time. Absent for legacy records
     *  without lifecycle tracking. */
    completedAt?: number;
    /**
     * True when the failure looked like a Copilot/upstream quota or
     * rate-limit exhaustion (vs. a generic transient error). Set by
     * the quota-pattern matcher in markFailed so the budget endpoint
     * can report "last quota-rejection seen at X" without re-parsing
     * every error message on every poll.
     */
    quotaExceeded?: boolean;
}

/** Snapshot of the operator-configured token budget vs current usage.
 *  Returned by /v1/budget so the harness can refuse to launch a batch
 *  that would push the bridge over its monthly quota. */
export interface BudgetSnapshot {
    /** ISO timestamp of the start of the current accounting window. */
    windowStartIso: string;
    /** ISO timestamp of when the window resets (exclusive). */
    windowEndIso: string;
    /** "monthly" — only window currently supported. Reserved field
     *  in case weekly / daily windows are added later. */
    windowKind: 'monthly';
    /** Operator-configured cap from VSCode settings; 0 means unlimited
     *  (no enforcement, just reporting). */
    budgetTokens: number;
    /** Sum of prompt + completion tokens across every successful call
     *  inside the current window. */
    usedTokens: number;
    /** Convenience: max(0, budgetTokens - usedTokens). 0 when budget=0
     *  (treat the field as "unknown / unlimited" in that case). */
    remainingTokens: number;
    /** Sum of estimatedCostUsd across the same window — the same
     *  per-model pricing the activity panel uses. */
    estimatedCostUsd: number;
    /** Most-recent ISO timestamp where a call failed with a quota-
     *  exhaustion-shaped error, or null if none in the window. */
    quotaExceededLastSeenIso: string | null;
    /** How many quota-exhaustion failures inside the window. */
    quotaExceededCount: number;
}

/**
 * Heuristic: does this error message look like a Copilot/upstream
 * quota or rate-limit exhaustion (vs. a generic transient error)?
 *
 * vscode.LanguageModelChat doesn't surface a typed error code we can
 * key off, and the underlying ChatQuotaExceeded / ChatRateLimited
 * names from the Copilot extension are private. The patterns below
 * cover what bubbles up via LanguageModelError.message in practice:
 * the Copilot extension stringifies its quota-exhaustion error as
 * "You've reached your monthly limit..." or similar, and HTTP 429s
 * get formatted with "rate limit" / "quota" / "exceeded" keywords.
 *
 * Conservative (false negatives over false positives): we'd rather
 * miss flagging a true quota error than incorrectly mark a generic
 * transient as quota-exhausted (which would make the budget endpoint
 * lie about the user's real GitHub state).
 */
export function isQuotaExhaustionError(msg: string | undefined | null): boolean {
    if (!msg) return false;
    const m = msg.toLowerCase();
    return (
        m.includes('quota') ||
        m.includes('rate limit') ||
        m.includes('rate_limit') ||
        m.includes('rate-limited') ||
        m.includes('reached your monthly') ||
        m.includes('monthly limit') ||
        m.includes('exceeded') && (m.includes('quota') || m.includes('limit')) ||
        m.includes('429')
    );
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
    /** Lifecycle status; absent = legacy record, treat as 'success'. */
    status?: CallStatus;
    /** When status='failed', the message the bridge surfaced. */
    errorMessage?: string;
    /** Bridge-internal request id; lets the UI dedupe pending → success
     *  state transitions visually. */
    reqId?: string;
    /** Wall-clock duration of the call in ms. For success/failed,
     *  this is completedAt - ts (true round-trip time). For pending,
     *  it's "now - ts" so the UI can show a live "Xs in flight" cell.
     *  null for legacy records without timestamps. */
    durationMs?: number | null;
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

    /**
     * Insert a 'pending' record the moment the bridge accepts a
     * request — BEFORE Copilot has responded. The UI shows it with a
     * "in flight" badge so the operator can see currently-active
     * traffic, not just completed transactions. Subsequently call
     * markSuccess(reqId, ...) or markFailed(reqId, ...) to mutate
     * the same record into its terminal state.
     */
    markPending(args: {
        reqId: string;
        modelId: string;
        client: string;
        promptText: string;
        via: 'socket' | 'openai-http';
        runId?: string;
    }): void {
        const promptTokens = approxTokens(args.promptText);
        const rec: RawRecord = {
            ts: Date.now(),
            modelId: args.modelId,
            client: args.client || 'anonymous',
            promptTokens,
            completionTokens: 0,
            estimatedCostUsd: estimateCost(args.modelId, promptTokens, 0),
            via: args.via,
            promptPreview: truncatePreview(args.promptText),
            completionPreview: undefined,
            runId: args.runId && args.runId.trim().length > 0 ? args.runId.trim() : undefined,
            reqId: args.reqId,
            status: 'pending',
        };
        this.records.push(rec);
        if (this.records.length > MAX_RECORDS) {
            this.records.splice(0, this.records.length - MAX_RECORDS);
        }
        this.fullBodies.set(rec.ts,
            { ts: rec.ts, promptFull: args.promptText, completionFull: '' });
        this.evictExcessFullBodies();
        this.context.globalState.update(STATE_KEY, this.records);
        this.fireListeners();
    }

    /** Mutate a previously-pending record into 'success' state. */
    markSuccess(args: {
        reqId: string;
        completionText: string;
    }): void {
        const rec = this.findByReqId(args.reqId);
        if (rec == null) {
            // Pending record never got created (older code path / race);
            // log won't break the chat but the UI loses the pending
            // → success transition for this call.
            return;
        }
        rec.completionTokens = approxTokens(args.completionText);
        rec.estimatedCostUsd = estimateCost(rec.modelId, rec.promptTokens, rec.completionTokens);
        rec.completionPreview = truncatePreview(args.completionText);
        rec.status = 'success';
        rec.completedAt = Date.now();
        const full = this.fullBodies.get(rec.ts);
        if (full != null) {
            this.fullBodies.set(rec.ts,
                { ts: rec.ts, promptFull: full.promptFull, completionFull: args.completionText });
        }
        this.context.globalState.update(STATE_KEY, this.records);
        this.fireListeners();
    }

    /** Mutate a previously-pending record into 'failed' state. */
    markFailed(args: { reqId: string; errorMessage: string }): void {
        const rec = this.findByReqId(args.reqId);
        if (rec == null) return;
        rec.status = 'failed';
        rec.errorMessage = (args.errorMessage || '').slice(0, 500);
        rec.quotaExceeded = isQuotaExhaustionError(args.errorMessage);
        rec.completedAt = Date.now();
        this.context.globalState.update(STATE_KEY, this.records);
        this.fireListeners();
    }

    /** Window-aware budget snapshot. budgetTokens=0 means the operator
     *  hasn't configured a cap — usedTokens is still reported so the
     *  harness can show consumption even without enforcement. */
    budgetSnapshot(budgetTokens: number): BudgetSnapshot {
        const now = new Date();
        const windowStart = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1));
        const windowEnd = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1));
        let used = 0;
        let cost = 0;
        let qLast: number | null = null;
        let qCount = 0;
        const startMs = windowStart.getTime();
        for (const r of this.records) {
            if (r.ts < startMs) continue;
            if (r.status === 'success' || r.status == null) {
                used += r.promptTokens + r.completionTokens;
                cost += r.estimatedCostUsd;
            }
            if (r.quotaExceeded) {
                qCount++;
                if (qLast == null || r.ts > qLast) qLast = r.ts;
            }
        }
        return {
            windowStartIso: windowStart.toISOString(),
            windowEndIso: windowEnd.toISOString(),
            windowKind: 'monthly',
            budgetTokens: budgetTokens > 0 ? budgetTokens : 0,
            usedTokens: used,
            remainingTokens: budgetTokens > 0 ? Math.max(0, budgetTokens - used) : 0,
            estimatedCostUsd: cost,
            quotaExceededLastSeenIso: qLast != null ? new Date(qLast).toISOString() : null,
            quotaExceededCount: qCount,
        };
    }

    private findByReqId(reqId: string): RawRecord | null {
        if (!reqId) return null;
        // Walk newest-first; bridge calls are short-lived and the
        // matching record is almost always at the tail.
        for (let i = this.records.length - 1; i >= 0; i--) {
            if (this.records[i].reqId === reqId) return this.records[i];
        }
        return null;
    }

    private evictExcessFullBodies(): void {
        if (this.fullBodies.size <= FULL_BODY_LIMIT) return;
        const it = this.fullBodies.keys();
        while (this.fullBodies.size > FULL_BODY_LIMIT) {
            const k = it.next().value;
            if (k === undefined) break;
            this.fullBodies.delete(k);
        }
    }

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
        const nowMs = Date.now();
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
            status: r.status ?? 'success',
            errorMessage: r.errorMessage,
            reqId: r.reqId,
            durationMs: r.completedAt != null ? r.completedAt - r.ts
                : (r.status === 'pending' ? nowMs - r.ts : null),
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
        const nowMsRun = Date.now();
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
                status: r.status ?? 'success',
                errorMessage: r.errorMessage,
                reqId: r.reqId,
                durationMs: r.completedAt != null ? r.completedAt - r.ts
                    : (r.status === 'pending' ? nowMsRun - r.ts : null),
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
