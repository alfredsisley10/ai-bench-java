package com.aibench.webui

import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.io.File

/**
 * Streams trace files out of the TraceManager's cache so the seed-audit
 * page can offer a "View JSON" link per trace.
 *
 * The existing /demo/appmap/view route only serves traces under
 * banking-app/tmp/appmap; ai-bench's shared trace cache lives at
 * ~/.ai-bench/appmap-traces/<sha>/<mode>/junit/<name>.appmap.json,
 * outside that scope. This controller is purpose-built for that
 * cache root; it serves the raw JSON inline in the browser tab so
 * the operator can spot-check a synthetic-stub body or, in Layer C,
 * a real recording. (The full embedded AppMap viewer requires
 * sequence-diagram + roots metadata that synthetic stubs don't
 * carry; a richer integration lands in Layer C.)
 *
 * Path-traversal guard: the canonical absolute path of the requested
 * file MUST sit inside the canonical cache root, otherwise 403. We
 * do NOT attempt to "fix up" suspicious paths — anything that
 * resolves outside the root is rejected outright.
 */
@RestController
class AppMapCacheController {

    private val cacheRoot: File =
        File(System.getProperty("user.home"), ".ai-bench/appmap-traces")

    @GetMapping("/appmap-cache/raw")
    fun raw(@RequestParam path: String): ResponseEntity<FileSystemResource> {
        val canonRoot = runCatching { cacheRoot.canonicalFile }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "trace cache not initialized")
        val file = runCatching { File(path).canonicalFile }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid path")
        if (!file.path.startsWith(canonRoot.path + File.separator)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "path outside trace cache")
        }
        if (!file.isFile) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "no such trace")
        }
        return ResponseEntity.ok()
            // Inline (not attachment) so the browser renders JSON in a
            // new tab rather than triggering a download. Filename is
            // still set for "Save As" if the operator wants to keep it.
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"${file.name}\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(FileSystemResource(file))
    }
}
