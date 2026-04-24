package com.aibench.config

sealed interface SecretSource {

    fun resolve(ref: String): String?

    companion object {
        fun forEnvironment(env: Environment, vaultCfg: BenchConfig.VaultSection): SecretSource {
            return ChainedSecretSource(buildList {
                add(EnvVarSecretSource)
                add(SystemPropertySecretSource)
                when (env) {
                    Environment.LOCAL -> add(OsKeystoreSecretSource())
                    Environment.OPENSHIFT_NONPROD, Environment.OPENSHIFT_PROD -> {
                        if (vaultCfg.enabled) add(VaultSecretSource(vaultCfg))
                    }
                }
            })
        }

        fun parse(ref: String): Pair<String, String> {
            val colonIdx = ref.indexOf(':')
            if (colonIdx < 1) return "literal" to ref
            return ref.substring(0, colonIdx) to ref.substring(colonIdx + 1)
        }
    }
}

internal class ChainedSecretSource(private val sources: List<SecretSource>) : SecretSource {
    override fun resolve(ref: String): String? {
        val (scheme, key) = SecretSource.parse(ref)
        for (source in sources) {
            val value = when (scheme) {
                "env" -> if (source is EnvVarSecretSource) source.resolve(key) else null
                "prop" -> if (source is SystemPropertySecretSource) source.resolve(key) else null
                "keystore" -> if (source is OsKeystoreSecretSource) source.resolve(key) else null
                "vault" -> if (source is VaultSecretSource) source.resolve(key) else null
                "literal" -> return key
                else -> source.resolve(ref)
            }
            if (value != null) return value
        }
        return null
    }
}

internal object EnvVarSecretSource : SecretSource {
    override fun resolve(ref: String): String? =
        System.getenv(ref) ?: System.getProperty("dotenv.$ref")
}

internal object SystemPropertySecretSource : SecretSource {
    override fun resolve(ref: String): String? = System.getProperty(ref)
}
