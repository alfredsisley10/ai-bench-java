package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody

/**
 * Tiny shared controller for the "Diagnose with LLM" feature family.
 * Exposes a JSON endpoint listing the LLM models the operator can
 * pick for any diagnose action, so each diagnose UI (banking-app
 * startup, AppMap trace recording, future ones) can populate its
 * model dropdown via the same fetch.
 *
 * Kept separate from DemoController / AdminTracesController so a
 * future feature page (eg. /admin/llm-config diagnose, /run-launcher
 * diagnose) doesn't need to depend on either of those controllers
 * just to reach the model picker data.
 */
@Controller
class DiagnoseController(
    private val llmDiagnostician: LlmDiagnostician
) {

    @GetMapping("/api/diagnose-models")
    @ResponseBody
    fun diagnoseModels(session: HttpSession): Map<String, Any?> {
        val models = llmDiagnostician.availableForDiagnose(session)
        return mapOf(
            "bridgeReachable" to llmDiagnostician.bridgeReachable(),
            "models" to models.map {
                mapOf(
                    "id" to it.id,
                    "displayName" to it.displayName,
                    "provider" to it.provider,
                    "modelIdentifier" to it.modelIdentifier
                )
            }
        )
    }
}
