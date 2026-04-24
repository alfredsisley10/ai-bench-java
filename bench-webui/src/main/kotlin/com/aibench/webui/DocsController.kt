package com.aibench.webui

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * Serves rendered help/operations documentation. The canonical source
 * lives in the docs/ markdown files; this controller exposes the
 * human-friendly HTML view that the WebUI links to.
 */
@Controller
class DocsController {

    @GetMapping("/docs/operations-guide")
    fun operationsGuide(): String = "operations-guide"
}
