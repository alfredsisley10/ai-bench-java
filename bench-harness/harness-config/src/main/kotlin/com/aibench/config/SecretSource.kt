package com.aibench.config

sealed interface SecretSource {

    val scheme: String

    fun resolve(key: String): String?

    companion object {
        fun forEnvironment(env: Environment, vaultCfg: BenchConfig.VaultSection): SecretSource =
            ChainedSecretSource(buildList {
                add(EnvVarSecretSource)
                add(SystemPropertySecretSource)
                when (env) {
                    Environment.LOCAL -> add(OsKeystoreSecretSource())
                    Environment.OPENSHIFT_NONPROD, Environment.OPENSHIFT_PROD -> {
                        if (vaultCfg.enabled) add(VaultSecretSource(vaultCfg))
                    }
                }
            })

        fun parse(ref: String): Pair<String, String> {
            val colonIdx = ref.indexOf(':')
            if (colonIdx < 1) return "literal" to ref
            return ref.substring(0, colonIdx) to ref.substring(colonIdx + 1)
        }
    }
}

internal class ChainedSecretSource(private val sources: List<SecretSource>) : SecretSource {
    override val scheme: String = "chain"

    override fun resolve(ref: String): String? {
        val (scheme, key) = SecretSource.parse(ref)
        if (scheme == "literal") return key
        return sources.firstOrNull { it.scheme == scheme }?.resolve(key)
    }
}

internal object EnvVarSecretSource : SecretSource {
    override val scheme: String = "env"
    override fun resolve(key: String): String? =
        System.getenv(key) ?: System.getProperty("dotenv.$key")
}

internal object SystemPropertySecretSource : SecretSource {
    override val scheme: String = "prop"
    override fun resolve(key: String): String? = System.getProperty(key)
}
