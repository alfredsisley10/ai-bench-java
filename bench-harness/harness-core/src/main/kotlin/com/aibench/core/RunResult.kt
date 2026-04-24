package com.aibench.core

import kotlinx.serialization.Serializable

@Serializable
data class RunResult(
    val runId: String,
    val bugId: String,
    val solver: String,
    val appmapMode: String,
    val seed: Long,
    val passed: Boolean,
    val promptTokens: Int,
    val completionTokens: Int,
    val wallMillis: Long,
    val patchLines: Int,
    val oracleLines: Int,
    val cyclomaticDelta: Int,
    val regressionsIntroduced: Int,
    val notes: String? = null
)
