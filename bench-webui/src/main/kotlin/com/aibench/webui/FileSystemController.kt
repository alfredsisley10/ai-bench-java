package com.aibench.webui

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.File

/**
 * Read-only filesystem listing for the in-page folder picker on /demo.
 * The webui runs on the operator's own machine so exposing directory
 * listings to the (locally-authenticated) user is intentional — it's
 * how the "Browse folders" button populates without forcing them to
 * type an absolute path.
 *
 * <p>Only directories are returned; hidden entries (dot-prefixed) are
 * dropped to keep the picker tidy. On Windows, when the user is at a
 * drive root, the response also includes the list of all drive letters
 * so they can hop sideways without having to know the exact drive.
 */
@RestController
class FileSystemController {

    data class FsEntry(val name: String, val path: String)

    data class FsListing(
        /** Absolute path the listing represents. */
        val path: String,
        /** Parent absolute path, or null at filesystem root. */
        val parent: String?,
        /** Subdirectories of [path], sorted alphabetically. */
        val entries: List<FsEntry>,
        /** Drive letters on Windows when at a drive root, else null. */
        val drives: List<String>?,
        /** User home — handy for the picker's "Home" button. */
        val home: String,
        /** True if [path] doesn't exist or isn't a directory. */
        val invalid: Boolean = false
    )

    @GetMapping("/api/fs/list")
    fun list(@RequestParam(required = false) path: String?): FsListing {
        val home = System.getProperty("user.home") ?: "/"
        val target = if (path.isNullOrBlank()) File(home) else File(path)
        if (!target.exists() || !target.isDirectory) {
            return FsListing(
                path = target.absolutePath, parent = null, entries = emptyList(),
                drives = if (Platform.isWindows) windowsDrives() else null,
                home = home, invalid = true
            )
        }
        val children = runCatching { target.listFiles() }.getOrNull() ?: emptyArray()
        val entries = children
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
            .map { FsEntry(it.name, it.absolutePath) }

        val parent = target.parentFile?.absolutePath
        // At a drive root on Windows we want the picker to be able to
        // hop to D:\ etc. Mac/Linux have a single root so drives stays
        // null there.
        val drives = if (Platform.isWindows && (parent == null || target.absolutePath.endsWith(":\\")))
            windowsDrives()
        else null

        return FsListing(target.absolutePath, parent, entries, drives, home)
    }

    private fun windowsDrives(): List<String> = runCatching {
        File.listRoots()?.map { it.absolutePath } ?: emptyList()
    }.getOrDefault(emptyList())
}
