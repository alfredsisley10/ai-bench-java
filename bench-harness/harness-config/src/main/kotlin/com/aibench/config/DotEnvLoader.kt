package com.aibench.config

import java.nio.file.Files
import java.nio.file.Path

object DotEnvLoader {

    fun load(dir: Path): Map<String, String> {
        val envFile = dir.resolve(".env")
        if (!Files.isRegularFile(envFile)) return emptyMap()
        return parse(Files.readString(envFile))
    }

    internal fun parse(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eqIndex = line.indexOf('=')
            if (eqIndex < 1) continue
            val key = line.substring(0, eqIndex).trim()
            var value = line.substring(eqIndex + 1).trim()
            if ((value.startsWith('"') && value.endsWith('"')) ||
                (value.startsWith('\'') && value.endsWith('\''))) {
                value = value.substring(1, value.length - 1)
            }
            result[key] = value
        }
        return result
    }
}
