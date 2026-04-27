package com.aibench.webui

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class CopilotGuideController {

    @GetMapping("/copilot-guide")
    fun guide(model: Model): String {
        val port = Platform.readCopilotPort()
        val endpoint = port?.let { "127.0.0.1:$it" } ?: Platform.copilotPortFile()
        val healthy = port != null && runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), 500); true
            }
        }.getOrDefault(false)
        model.addAttribute("copilotSockPath", endpoint)
        model.addAttribute("bridgeHealthy", healthy)
        return "copilot-guide"
    }
}
