package com.aibench.webui

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class CopilotGuideController {

    @GetMapping("/copilot-guide")
    fun guide(model: Model): String {
        val copilotSock = Platform.defaultCopilotSocket()
        model.addAttribute("copilotSockPath", copilotSock)
        model.addAttribute("bridgeHealthy", java.io.File(copilotSock).exists())
        return "copilot-guide"
    }
}
