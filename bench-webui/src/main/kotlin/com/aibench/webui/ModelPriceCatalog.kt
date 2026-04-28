package com.aibench.webui

import org.springframework.stereotype.Component

/**
 * Catalog of publicly-published per-1K-token prices for common LLMs.
 * Used by the Add-Model form to prefill the cost fields so operators
 * don't have to hand-enter them.
 *
 * <p>Prices are snapshots at the knowledge cutoff of this build
 * (January 2026). Corporate OpenAI gateways usually charge internal
 * departments a different rate; the operator can override after
 * adding a model. Enterprise-only models (e.g. Azure OpenAI PTUs) are
 * NOT listed here — pricing is customer-specific.
 *
 * <p>All prices are in USD per 1,000 tokens. A null completion price
 * marks the model as prompt-only (embeddings, moderations).
 */
@Component
class ModelPriceCatalog {

    /** ISO-8601 date the prices below were last cross-referenced. */
    val lastUpdated: String = "2026-04-28"

    /**
     * Where to verify or update the per-1K-token rates. Listed by
     * provider so an operator who notices a stale rate can crib the
     * authoritative number from the vendor page directly.
     */
    val sources: List<Pair<String, String>> = listOf(
        "OpenAI" to "https://openai.com/api/pricing/",
        "Anthropic" to "https://www.anthropic.com/pricing",
        "Google (Gemini)" to "https://ai.google.dev/pricing",
        "Meta (Llama via Together AI reference rate)" to "https://www.together.ai/pricing",
        "Mistral" to "https://mistral.ai/technology/#pricing",
        "GitHub Copilot (seat pricing — no per-token charge)" to "https://github.com/features/copilot/plans"
    )

    data class ModelPrice(
        val id: String,
        val displayName: String,
        val provider: String,
        val modelIdentifier: String,
        val costPer1kPrompt: Double,
        val costPer1kCompletion: Double?,
        val modality: String,
        val notes: String = ""
    )

    /**
     * Publicly-listed prices as of 2026-01. When the corporate gateway
     * rebates these through Apigee the internal chargeback may differ;
     * operators can override after adding the model.
     */
    val catalog: List<ModelPrice> = listOf(
        // --- OpenAI ------------------------------------------------------
        ModelPrice("openai-gpt-4o", "GPT-4o", "corp-openai", "gpt-4o", 0.0025, 0.010, "chat"),
        ModelPrice("openai-gpt-4o-mini", "GPT-4o mini", "corp-openai", "gpt-4o-mini", 0.00015, 0.0006, "chat"),
        ModelPrice("openai-gpt-4-turbo", "GPT-4 Turbo", "corp-openai", "gpt-4-turbo", 0.010, 0.030, "chat"),
        ModelPrice("openai-gpt-4", "GPT-4", "corp-openai", "gpt-4", 0.030, 0.060, "chat"),
        ModelPrice("openai-gpt-3-5-turbo", "GPT-3.5 Turbo", "corp-openai", "gpt-3.5-turbo", 0.0005, 0.0015, "chat"),
        ModelPrice("openai-o1-preview", "o1 preview", "corp-openai", "o1-preview", 0.015, 0.060, "chat",
                   notes = "reasoning — higher output cost"),
        ModelPrice("openai-o1-mini", "o1 mini", "corp-openai", "o1-mini", 0.003, 0.012, "chat",
                   notes = "reasoning"),
        ModelPrice("openai-embed-3-small", "text-embedding-3-small", "corp-openai", "text-embedding-3-small",
                   0.00002, null, "embedding"),
        ModelPrice("openai-embed-3-large", "text-embedding-3-large", "corp-openai", "text-embedding-3-large",
                   0.00013, null, "embedding"),

        // --- Anthropic (via direct API; not through corp-openai) --------
        ModelPrice("anthropic-claude-3-5-sonnet", "Claude 3.5 Sonnet", "anthropic", "claude-3-5-sonnet-latest",
                   0.003, 0.015, "chat"),
        ModelPrice("anthropic-claude-3-5-haiku", "Claude 3.5 Haiku", "anthropic", "claude-3-5-haiku-latest",
                   0.0008, 0.004, "chat"),
        ModelPrice("anthropic-claude-3-opus", "Claude 3 Opus", "anthropic", "claude-3-opus-20240229",
                   0.015, 0.075, "chat"),
        ModelPrice("anthropic-claude-3-sonnet", "Claude 3 Sonnet", "anthropic", "claude-3-sonnet-20240229",
                   0.003, 0.015, "chat"),
        ModelPrice("anthropic-claude-3-haiku", "Claude 3 Haiku", "anthropic", "claude-3-haiku-20240307",
                   0.00025, 0.00125, "chat"),

        // --- Google -----------------------------------------------------
        ModelPrice("google-gemini-1-5-pro", "Gemini 1.5 Pro (<128K ctx)", "google", "gemini-1.5-pro",
                   0.00125, 0.005, "chat", notes = "above 128K: 2x rate"),
        ModelPrice("google-gemini-1-5-flash", "Gemini 1.5 Flash (<128K ctx)", "google", "gemini-1.5-flash",
                   0.000075, 0.0003, "chat", notes = "above 128K: 2x rate"),

        // --- Meta / Llama (via typical provider price) ------------------
        ModelPrice("meta-llama-3-1-405b", "Llama 3.1 405B (reference rate)", "meta", "llama-3.1-405b-instruct",
                   0.005, 0.015, "chat", notes = "actual depends on hosting provider"),
        ModelPrice("meta-llama-3-1-70b", "Llama 3.1 70B", "meta", "llama-3.1-70b-instruct",
                   0.00088, 0.00088, "chat"),
        ModelPrice("meta-llama-3-1-8b", "Llama 3.1 8B", "meta", "llama-3.1-8b-instruct",
                   0.00018, 0.00018, "chat"),

        // --- Mistral ----------------------------------------------------
        ModelPrice("mistral-large", "Mistral Large", "mistral", "mistral-large-latest",
                   0.002, 0.006, "chat"),
        ModelPrice("mistral-small", "Mistral Small", "mistral", "mistral-small-latest",
                   0.0002, 0.0006, "chat"),
        ModelPrice("mistral-nemo", "Mistral Nemo", "mistral", "open-mistral-nemo",
                   0.00015, 0.00015, "chat"),

        // --- GitHub Copilot tiers (flat-rate seat pricing) -------------
        // No per-token charge: per-1K fields stay at 0 so cost-per-run
        // computations roll to $0 for the run itself; the seat price is
        // captured in the notes column for chargeback / reference.
        ModelPrice("copilot-free", "GitHub Copilot Free", "copilot", "copilot",
                   0.0, 0.0, "chat",
                   notes = "free tier — limited model access; 50 chat msgs/mo, 2k completions/mo"),
        ModelPrice("copilot-pro", "GitHub Copilot Pro", "copilot", "copilot",
                   0.0, 0.0, "chat",
                   notes = "\$10/seat/mo — unlimited completions, GPT-4o + Claude 3.5 Sonnet chat"),
        ModelPrice("copilot-pro-plus", "GitHub Copilot Pro+", "copilot", "copilot",
                   0.0, 0.0, "chat",
                   notes = "\$39/seat/mo — Pro plus access to premium reasoning models (o1)"),
        ModelPrice("copilot-business", "GitHub Copilot Business", "copilot", "copilot",
                   0.0, 0.0, "chat",
                   notes = "\$19/seat/mo — org-managed; admin policies; IP indemnification; data not used to train"),
        ModelPrice("copilot-enterprise", "GitHub Copilot Enterprise", "copilot", "copilot",
                   0.0, 0.0, "chat",
                   notes = "\$39/seat/mo — Business + GitHub.com integration, org-aware chat, fine-tuning add-on"),

        // --- Copilot-served LLMs (seat-priced, reference rates only) ----
        // GitHub Copilot doesn't bill per-token; the operator's seat is
        // already paid. Listing these with the *underlying* model's
        // public per-token rate gives the run-cost estimator something
        // realistic to chargeback an internal cost center against —
        // "what would this run cost if billed at OpenAI/Anthropic's
        // direct rate?". The notes field marks the entry as estimative.
        // Source rates match the OpenAI / Anthropic / Google entries
        // above as of `lastUpdated`. Update both halves together when
        // a vendor moves a price.
        //
        // Copilot model routing per
        // https://docs.github.com/en/copilot/using-github-copilot/ai-models/changing-the-ai-model-for-copilot-chat
        // (catalog last cross-checked at lastUpdated above).
        ModelPrice("copilot-gpt-4o", "GPT-4o (via Copilot)", "copilot", "gpt-4o",
                   0.0025, 0.010, "chat",
                   notes = "billed via Copilot seat — \$0.0025/\$0.010 per 1K is the OpenAI direct rate, shown here for chargeback estimation"),
        ModelPrice("copilot-gpt-4o-mini", "GPT-4o mini (via Copilot)", "copilot", "gpt-4o-mini",
                   0.00015, 0.0006, "chat",
                   notes = "Copilot seat-priced; rates mirror OpenAI direct"),
        ModelPrice("copilot-gpt-4-1", "GPT-4.1 (via Copilot)", "copilot", "gpt-4.1",
                   0.002, 0.008, "chat",
                   notes = "Copilot seat-priced; rates mirror OpenAI direct (2024-04 pricing)"),
        ModelPrice("copilot-gpt-4", "GPT-4 (via Copilot)", "copilot", "gpt-4",
                   0.030, 0.060, "chat",
                   notes = "Copilot seat-priced; rates mirror OpenAI direct"),
        ModelPrice("copilot-gpt-3-5-turbo", "GPT-3.5 Turbo (via Copilot)", "copilot", "gpt-3.5-turbo",
                   0.0005, 0.0015, "chat",
                   notes = "Copilot seat-priced; rates mirror OpenAI direct"),
        ModelPrice("copilot-o1", "o1 (via Copilot)", "copilot", "o1",
                   0.015, 0.060, "chat",
                   notes = "reasoning — high output cost. Pro+ / Enterprise tier on Copilot"),
        ModelPrice("copilot-o1-mini", "o1-mini (via Copilot)", "copilot", "o1-mini",
                   0.003, 0.012, "chat",
                   notes = "reasoning. Pro+ / Enterprise tier"),
        ModelPrice("copilot-o3-mini", "o3-mini (via Copilot)", "copilot", "o3-mini",
                   0.0011, 0.0044, "chat",
                   notes = "reasoning, smaller. Pro+ / Enterprise tier"),
        ModelPrice("copilot-claude-3-5-sonnet", "Claude 3.5 Sonnet (via Copilot)", "copilot", "claude-3-5-sonnet",
                   0.003, 0.015, "chat",
                   notes = "Copilot seat-priced; rates mirror Anthropic direct"),
        ModelPrice("copilot-claude-3-7-sonnet", "Claude 3.7 Sonnet (via Copilot)", "copilot", "claude-3-7-sonnet",
                   0.003, 0.015, "chat",
                   notes = "Copilot seat-priced; rates mirror Anthropic direct"),
        ModelPrice("copilot-claude-3-5-haiku", "Claude 3.5 Haiku (via Copilot)", "copilot", "claude-3-5-haiku",
                   0.0008, 0.004, "chat",
                   notes = "Copilot seat-priced; rates mirror Anthropic direct"),
        ModelPrice("copilot-gemini-1-5-pro", "Gemini 1.5 Pro (via Copilot)", "copilot", "gemini-1.5-pro",
                   0.00125, 0.005, "chat",
                   notes = "Copilot seat-priced; rates mirror Google direct (≤128K ctx; 2x above)"),
        ModelPrice("copilot-gemini-2-0-flash", "Gemini 2.0 Flash (via Copilot)", "copilot", "gemini-2.0-flash",
                   0.00010, 0.00040, "chat",
                   notes = "Copilot seat-priced; rates mirror Google direct"),
        // --- Newer Copilot routes added since the 2026-01 snapshot ----
        // Copilot's catalog now surfaces Claude Sonnet 4.5/4.6,
        // GPT-5.x, Gemini 3.x preview, and a couple of router aliases
        // (copilot-default, copilot-auto, copilot-fast). Per-1K rates
        // below mirror the underlying vendor's direct API price (or a
        // best-effort estimate when only preview pricing is published).
        // Estimates are flagged in `notes` so operators can see at a
        // glance which numbers are authoritative vs. provisional.
        ModelPrice("copilot-claude-sonnet-4-5", "Claude Sonnet 4.5 (via Copilot)", "copilot", "claude-sonnet-4.5",
                   0.003, 0.015, "chat",
                   notes = "Copilot seat-priced; rates mirror Anthropic Claude Sonnet direct (0.003/0.015 per 1K is the published 4.x Sonnet rate)"),
        ModelPrice("copilot-claude-sonnet-4-6", "Claude Sonnet 4.6 (via Copilot)", "copilot", "claude-sonnet-4.6",
                   0.003, 0.015, "chat",
                   notes = "Copilot seat-priced; rates mirror Anthropic Claude Sonnet direct"),
        ModelPrice("copilot-gpt-5-2", "GPT-5.2 (via Copilot)", "copilot", "gpt-5.2",
                   0.005, 0.015, "chat",
                   notes = "Copilot seat-priced; estimated rate based on OpenAI GPT-5 family — verify against openai.com/api/pricing when invoiced"),
        ModelPrice("copilot-gpt-5-4", "GPT-5.4 (via Copilot)", "copilot", "gpt-5.4",
                   0.005, 0.015, "chat",
                   notes = "Copilot seat-priced; estimated rate based on OpenAI GPT-5 family"),
        ModelPrice("copilot-gemini-3-1-pro-preview", "Gemini 3.1 Pro Preview (via Copilot)", "copilot", "gemini-3.1-pro-preview",
                   0.00125, 0.005, "chat",
                   notes = "Copilot seat-priced; preview — estimated rate matches Gemini 1.5 Pro direct (≤128K ctx)"),
        ModelPrice("copilot-gemini-3-flash-preview", "Gemini 3 Flash Preview (via Copilot)", "copilot", "gemini-3-flash-preview",
                   0.0001, 0.0004, "chat",
                   notes = "Copilot seat-priced; preview — estimated rate matches Gemini 2.0 Flash direct"),
        // Router aliases — Copilot picks an underlying model at request
        // time. Listing them at gpt-4o's rate gives the cost estimator
        // a sane default when the operator can't see what model Copilot
        // resolved to; the run record's modelId is the alias, not the
        // resolved model, so a single rate is the best we can offer.
        ModelPrice("copilot-default", "Copilot (default model)", "copilot", "copilot",
                   0.0025, 0.010, "chat",
                   notes = "Copilot seat-priced; default route currently resolves to GPT-4o — rate shown is the GPT-4o direct rate"),
        ModelPrice("copilot-auto", "Copilot Auto Router (via Copilot)", "copilot", "auto",
                   0.0025, 0.010, "chat",
                   notes = "Copilot seat-priced; router picks per request — rate estimated at the GPT-4o direct rate (most-common backend)"),
        ModelPrice("copilot-copilot-fast", "Copilot Fast (via Copilot)", "copilot", "copilot-fast",
                   0.00015, 0.0006, "chat",
                   notes = "Copilot seat-priced; latency-optimised route — rate estimated at GPT-4o-mini's direct rate"),

        // --- AppMap Navie (rides on top of Copilot via the bridge) ----
        // No incremental per-token charge above what Copilot already
        // costs the seat. Listed here so cost-per-run computations
        // resolve to $0 (matching the seat-priced reality) and so the
        // catalog table documents the routing model for ops/finance.
        ModelPrice("appmap-navie-via-copilot", "AppMap Navie via Copilot",
                   "appmap-navie", "navie-via-copilot",
                   0.0, 0.0, "chat",
                   notes = "AppMap CLI's Navie context engine; LLM calls flow through the local Copilot bridge — Copilot seat price applies, no separate Navie token charge")
    )
}
