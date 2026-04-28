// Embedded per-1K-token price table mirrored from bench-webui's
// ModelPriceCatalog. Kept in-extension so cost estimates work even
// when bench-webui is offline. Update both sides in lock step.
//
// IMPORTANT: patterns are checked in order — list more-specific
// versions BEFORE less-specific ones. `^gpt-4o/i` would match
// "gpt-4o-mini" if it appeared first; `^gpt-4/i` would mis-attribute
// "gpt-4.1" to GPT-4 ($0.030/$0.060) instead of GPT-4.1 ($0.002/$0.008)
// if the more-specific pattern doesn't run first. The current order
// is intentional.
//
// Last cross-referenced: 2026-04-28

export interface ModelPrice {
    pattern: RegExp;
    promptPer1k: number;
    completionPer1k: number;
    label: string;
}

const TABLE: ModelPrice[] = [
    // OpenAI — newer-first so e.g. gpt-4.1 doesn't get caught by ^gpt-4
    { pattern: /^gpt-5(\.|$)/i, promptPer1k: 0.005, completionPer1k: 0.015, label: 'GPT-5' },
    { pattern: /^gpt-4\.1/i, promptPer1k: 0.002, completionPer1k: 0.008, label: 'GPT-4.1' },
    { pattern: /^gpt-4o-mini/i, promptPer1k: 0.00015, completionPer1k: 0.0006, label: 'GPT-4o mini' },
    { pattern: /^gpt-4o/i, promptPer1k: 0.0025, completionPer1k: 0.010, label: 'GPT-4o' },
    { pattern: /^gpt-4-turbo/i, promptPer1k: 0.010, completionPer1k: 0.030, label: 'GPT-4 Turbo' },
    { pattern: /^gpt-4/i, promptPer1k: 0.030, completionPer1k: 0.060, label: 'GPT-4' },
    { pattern: /^gpt-3\.5/i, promptPer1k: 0.0005, completionPer1k: 0.0015, label: 'GPT-3.5 Turbo' },
    { pattern: /^o3-mini/i, promptPer1k: 0.0011, completionPer1k: 0.0044, label: 'o3-mini' },
    { pattern: /^o1-preview/i, promptPer1k: 0.015, completionPer1k: 0.060, label: 'o1 preview' },
    { pattern: /^o1-mini/i, promptPer1k: 0.003, completionPer1k: 0.012, label: 'o1 mini' },
    { pattern: /^o1(\b|-)/i, promptPer1k: 0.015, completionPer1k: 0.060, label: 'o1' },
    // Anthropic — newer Sonnet revisions first so they don't get caught
    // by the older claude-3-sonnet pattern. Copilot exposes 4.x as
    // claude-sonnet-4.5 / claude-sonnet-4.6 (no version-prefix-3 in
    // the slug).
    { pattern: /claude-sonnet-4\.6/i, promptPer1k: 0.003, completionPer1k: 0.015, label: 'Claude Sonnet 4.6' },
    { pattern: /claude-sonnet-4\.5/i, promptPer1k: 0.003, completionPer1k: 0.015, label: 'Claude Sonnet 4.5' },
    { pattern: /claude-3-7-sonnet/i, promptPer1k: 0.003, completionPer1k: 0.015, label: 'Claude 3.7 Sonnet' },
    { pattern: /claude-3-5-sonnet/i, promptPer1k: 0.003, completionPer1k: 0.015, label: 'Claude 3.5 Sonnet' },
    { pattern: /claude-3-5-haiku/i, promptPer1k: 0.0008, completionPer1k: 0.004, label: 'Claude 3.5 Haiku' },
    { pattern: /claude-3-opus/i, promptPer1k: 0.015, completionPer1k: 0.075, label: 'Claude 3 Opus' },
    { pattern: /claude-3-sonnet/i, promptPer1k: 0.003, completionPer1k: 0.015, label: 'Claude 3 Sonnet' },
    { pattern: /claude-3-haiku/i, promptPer1k: 0.00025, completionPer1k: 0.00125, label: 'Claude 3 Haiku' },
    // Google — preview/3.x first so they don't fall through to 1.5.
    { pattern: /gemini-3.*pro/i, promptPer1k: 0.00125, completionPer1k: 0.005, label: 'Gemini 3 Pro (preview)' },
    { pattern: /gemini-3.*flash/i, promptPer1k: 0.0001, completionPer1k: 0.0004, label: 'Gemini 3 Flash (preview)' },
    { pattern: /gemini-2\.0-flash/i, promptPer1k: 0.0001, completionPer1k: 0.0004, label: 'Gemini 2.0 Flash' },
    { pattern: /gemini-1\.5-pro/i, promptPer1k: 0.00125, completionPer1k: 0.005, label: 'Gemini 1.5 Pro' },
    { pattern: /gemini-1\.5-flash/i, promptPer1k: 0.000075, completionPer1k: 0.0003, label: 'Gemini 1.5 Flash' },
    // Meta (Together AI reference rate)
    { pattern: /llama-3\.1-405b/i, promptPer1k: 0.005, completionPer1k: 0.015, label: 'Llama 3.1 405B' },
    { pattern: /llama-3\.1-70b/i, promptPer1k: 0.00088, completionPer1k: 0.00088, label: 'Llama 3.1 70B' },
    { pattern: /llama-3\.1-8b/i, promptPer1k: 0.00018, completionPer1k: 0.00018, label: 'Llama 3.1 8B' },
    // Copilot router aliases — `auto` and the bridge-default `copilot`
    // resolve at request time; cost estimated at GPT-4o's direct rate
    // (Copilot's most-common backend) so the stats panel doesn't show
    // $0 for runs that genuinely consumed tokens. `copilot-fast` is a
    // latency-tier alias estimated at GPT-4o-mini's rate.
    { pattern: /^copilot-fast$/i, promptPer1k: 0.00015, completionPer1k: 0.0006, label: 'Copilot Fast' },
    { pattern: /^copilot$/i, promptPer1k: 0.0025, completionPer1k: 0.010, label: 'Copilot (default route)' },
    { pattern: /^auto$/i, promptPer1k: 0.0025, completionPer1k: 0.010, label: 'Copilot Auto' },
];

/** Best-effort price lookup by model id / family. Returns null when the
 *  model is unknown to the catalog (caller should display "—" for cost). */
export function priceFor(modelId: string): ModelPrice | null {
    for (const e of TABLE) {
        if (e.pattern.test(modelId)) return e;
    }
    return null;
}

/** Coarse char→token approximation: ~4 chars per token across English
 *  + English-flavored programming languages. Real tokenizers vary
 *  per-model; we surface this approximation note in the UI. */
export function approxTokens(text: string): number {
    if (!text) return 0;
    return Math.max(1, Math.ceil(text.length / 4));
}

export function estimateCost(modelId: string, promptTokens: number, completionTokens: number): number {
    const p = priceFor(modelId);
    if (!p) return 0;
    return (promptTokens / 1000) * p.promptPer1k + (completionTokens / 1000) * p.completionPer1k;
}
