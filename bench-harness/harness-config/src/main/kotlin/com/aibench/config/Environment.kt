package com.aibench.config

enum class Environment(val key: String) {
    LOCAL("local"),
    OPENSHIFT_NONPROD("openshift-nonprod"),
    OPENSHIFT_PROD("openshift-prod");

    companion object {
        fun detect(): Environment {
            val envKey = System.getenv("AI_BENCH_ENV") ?: "local"
            return entries.firstOrNull { it.key == envKey }
                ?: throw IllegalStateException("Unknown environment: $envKey (expected one of: ${entries.joinToString { it.key }})")
        }
    }
}
