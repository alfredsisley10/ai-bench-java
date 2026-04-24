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
    val lastUpdated: String = "2026-01-15"

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
