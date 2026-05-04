package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Operator surface for the model-pricing table.
 *
 * Pricing flows in two directions:
 *   - Pull: bench-webui's local store can be replaced with whatever
 *     the bridge currently has (useful on first run, or after a VSIX
 *     upgrade ships new defaults).
 *   - Push: an operator-edited local store can be sent to the bridge
 *     so the activity panel's per-call cost matches what the harness
 *     uses for its leaderboard cost columns.
 *
 * The page renders a side-by-side diff of "what's in the bench-webui
 * store" vs "what's currently on the bridge" so the operator can see
 * drift before deciding which way to sync.
 */
@Controller
class AdminPricingController(
    private val pricingStore: ModelPricingStore
) {
    private val log = LoggerFactory.getLogger(AdminPricingController::class.java)

    @GetMapping("/admin/pricing")
    fun page(model: Model, session: HttpSession): String {
        val local = pricingStore.state()
        val bridge = pricingStore.bridgeSnapshot()
        model.addAttribute("local", local)
        model.addAttribute("bridge", bridge)
        model.addAttribute("hasLocal", local != null)
        model.addAttribute("hasBridge", bridge != null)
        // Drift summary: count entries where the (label,prompt,completion)
        // tuple differs between local and bridge. Empty when either side
        // is missing -- the operator's cue is to pull or push first.
        val driftCount = if (local != null && bridge != null) {
            val byLabel = bridge.entries.associateBy { it.label }
            local.entries.count { l ->
                val b = byLabel[l.label]
                b == null ||
                    b.promptPer1k != l.promptPer1k ||
                    b.completionPer1k != l.completionPer1k
            }
        } else 0
        model.addAttribute("driftCount", driftCount)
        // Surface one-shot toast.
        model.addAttribute("pricingResult", session.getAttribute("pricingResult"))
        session.removeAttribute("pricingResult")
        return "admin-pricing"
    }

    /** Replace local store with whatever the bridge currently has. */
    @PostMapping("/admin/pricing/pull")
    fun pull(session: HttpSession): String {
        return runCatching {
            val s = pricingStore.pullFromBridge()
            session.setAttribute("pricingResult",
                "Pulled ${s.entries.size} pricing entries from bridge.")
            "redirect:/admin/pricing"
        }.getOrElse { e ->
            log.warn("pricing pull failed: {}", e.message)
            session.setAttribute("pricingResult", "Pull failed: ${e.message}")
            "redirect:/admin/pricing"
        }
    }

    /** Push local store to bridge so the VSIX activity panel uses
     *  the operator's edited pricing instead of its baked defaults. */
    @PostMapping("/admin/pricing/push")
    fun push(session: HttpSession): String {
        val ok = pricingStore.pushToBridge()
        session.setAttribute("pricingResult", if (ok)
            "Pushed local pricing to bridge."
        else
            "Push failed (bridge unreachable or local store empty).")
        return "redirect:/admin/pricing"
    }

    /** Wipe the local store -- next page render will prompt the
     *  operator to pull from the bridge. */
    @PostMapping("/admin/pricing/clear-local")
    fun clearLocal(session: HttpSession): String {
        pricingStore.clearLocal()
        session.setAttribute("pricingResult", "Local pricing store cleared.")
        return "redirect:/admin/pricing"
    }

    /**
     * Add bench-webui's built-in patterns for newer models (Claude
     * Opus 4.6, GPT-5 Mini, Gemini 2.5 Pro, etc.) that the operator's
     * pricing.json may not have yet. Existing entries are NEVER
     * overwritten; only missing patterns are prepended (so the more
     * specific new patterns beat generic fallbacks like
     * `^gpt-5(\.|$)` matching `gpt-5-mini`).
     */
    @PostMapping("/admin/pricing/seed-missing")
    fun seedMissing(session: HttpSession): String {
        val added = pricingStore.seedMissingPatterns()
        session.setAttribute("pricingResult",
            if (added > 0) "Seeded $added missing pattern(s) for newer models. Push to bridge to sync."
            else "All built-in patterns already present in local store.")
        return "redirect:/admin/pricing"
    }

    /** Save edits from the form. The form posts parallel arrays --
     *  pattern[i], flags[i], promptPer1k[i], completionPer1k[i],
     *  label[i] -- so the operator can edit any cell without
     *  per-row htmx round-trips. Empty rows are dropped. */
    @PostMapping("/admin/pricing/save")
    fun save(
        @RequestParam(required = false) pattern: List<String>?,
        @RequestParam(required = false) flags: List<String>?,
        @RequestParam(required = false) promptPer1k: List<String>?,
        @RequestParam(required = false) completionPer1k: List<String>?,
        @RequestParam(required = false) label: List<String>?,
        session: HttpSession
    ): String {
        val patterns = pattern ?: emptyList()
        val flagsL = flags ?: emptyList()
        val ppL = promptPer1k ?: emptyList()
        val cpL = completionPer1k ?: emptyList()
        val labelsL = label ?: emptyList()
        val n = patterns.size
        val entries = (0 until n).mapNotNull { i ->
            val p = patterns.getOrNull(i)?.trim().orEmpty()
            if (p.isEmpty()) return@mapNotNull null
            val pp = ppL.getOrNull(i)?.toDoubleOrNull() ?: return@mapNotNull null
            val cp = cpL.getOrNull(i)?.toDoubleOrNull() ?: return@mapNotNull null
            ModelPricingStore.Entry(
                pattern = p,
                flags = flagsL.getOrNull(i)?.trim().orEmpty(),
                promptPer1k = pp,
                completionPer1k = cp,
                label = labelsL.getOrNull(i)?.trim().orEmpty()
            )
        }
        if (entries.isEmpty()) {
            session.setAttribute("pricingResult", "No valid rows to save.")
            return "redirect:/admin/pricing"
        }
        pricingStore.replace(entries)
        session.setAttribute("pricingResult",
            "Saved ${entries.size} pricing entries locally. Push to bridge to sync.")
        return "redirect:/admin/pricing"
    }
}
