package com.aibench.webui

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

/**
 * Thin UI wrapper over {@link ConnectionSettings}. All outbound HTTP
 * clients in the WebUI should build through {@code ConnectionSettings}
 * so the choices made here propagate everywhere.
 */
@Controller
class ProxyConfigController(
    private val connectionSettings: ConnectionSettings
) {

    @GetMapping("/proxy")
    fun proxyConfig(model: Model, session: HttpSession): String {
        model.addAttribute("settings", connectionSettings.settings)
        model.addAttribute("gradleSystemProps", connectionSettings.gradleSystemProps())
        model.addAttribute("saveResult", session.getAttribute("proxySaveResult"))
        session.removeAttribute("proxySaveResult")
        return "proxy-config"
    }

    @PostMapping("/proxy/save")
    fun save(
        @RequestParam httpsProxy: String,
        @RequestParam httpProxy: String,
        @RequestParam noProxy: String,
        @RequestParam(required = false, defaultValue = "false") insecureSsl: Boolean,
        session: HttpSession
    ): String {
        connectionSettings.update(
            ConnectionSettings.Settings(
                httpsProxy = httpsProxy.trim(),
                httpProxy = httpProxy.trim(),
                noProxy = noProxy.trim(),
                insecureSsl = insecureSsl,
                source = "manual"
            )
        )
        session.setAttribute(
            "proxySaveResult",
            if (insecureSsl)
                "Proxy + TLS settings saved. SSL verification is DISABLED for outbound WebUI connections."
            else
                "Proxy settings saved and Gradle properties updated."
        )
        return "redirect:/proxy"
    }

    @PostMapping("/proxy/reset")
    fun reset(session: HttpSession): String {
        connectionSettings.resetToDetected()
        session.setAttribute("proxySaveResult", "Connection settings reset to auto-detected defaults.")
        return "redirect:/proxy"
    }

    @GetMapping("/api/proxy")
    @ResponseBody
    fun apiProxySettings(): ConnectionSettings.Settings = connectionSettings.settings
}
