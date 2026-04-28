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

    /**
     * Standalone Swagger UI page. Replaces the iframe that used to live
     * at the bottom of /demo. Lifted out so it gets its own admin-nav
     * entry and can host both the `demo` and `internal` OpenAPI groups
     * (the embedded version was hard-pinned to `demo` only).
     */
    @GetMapping("/api-docs")
    fun apiDocs(): String = "api-docs"
}
