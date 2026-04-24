package com.aibench.core

data class RunRequest(
    val bug: Bug,
    val solver: String,
    val appmapMode: AppMapMode,
    val seed: Long,
    val workRoot: java.nio.file.Path
) {
    enum class AppMapMode { OFF, ON_RECOMMENDED, ON_ALL }
}
