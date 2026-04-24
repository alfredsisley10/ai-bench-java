package com.aibench.core

import com.charleskorn.kaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Reads the bugs/ directory. Each .yaml file is one Bug.
 */
class BugCatalog(private val root: Path) {

    fun load(): List<Bug> =
        Files.list(root).use { stream ->
            stream.filter { it.extension == "yaml" }
                .map { Yaml.default.decodeFromString(Bug.serializer(), it.readText()) }
                .toList()
        }

    fun byId(id: String): Bug =
        load().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown bug: $id")
}
