package com.aibench.webui

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import java.io.File

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

    /**
     * Generic markdown viewer. Resolves to `docs/{name}.md` relative to
     * either the JVM's working directory or one level up (bench-webui
     * usually launches from `bench-webui/` with `docs/` at the repo
     * root). Renders through CommonMark + GFM tables.
     *
     * Path traversal is rejected outright -- the name must match a safe
     * regex AND the resolved file must sit inside the canonical docs
     * directory.
     */
    @GetMapping("/docs/{name}")
    fun markdownDoc(@PathVariable name: String, model: Model): String {
        if (!name.matches(SAFE_NAME)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "no such doc")
        }
        val docFile = resolveDocFile(name)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no such doc")
        val md = docFile.readText()
        val parser = Parser.builder().extensions(EXTENSIONS).build()
        val renderer = HtmlRenderer.builder().extensions(EXTENSIONS).build()
        val html = renderer.render(parser.parse(md))
        model.addAttribute("docName", name)
        model.addAttribute("docTitle", deriveTitle(md, name))
        model.addAttribute("docHtml", html)
        return "docs-page"
    }

    private fun deriveTitle(md: String, fallback: String): String {
        val h1 = md.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
        return h1 ?: fallback
    }

    private fun resolveDocFile(name: String): File? {
        val candidates = listOf(File("docs"), File("../docs"), File("../../docs"))
        for (dir in candidates) {
            if (!dir.isDirectory) continue
            val canonicalDir = dir.canonicalFile
            val target = File(canonicalDir, "$name.md").canonicalFile
            if (target.isFile && target.parentFile == canonicalDir) return target
        }
        return null
    }

    companion object {
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")
        private val EXTENSIONS = listOf(TablesExtension.create())
    }
}
