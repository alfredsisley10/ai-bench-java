package com.aibench.llm.corp

import com.aibench.llm.LlmClient
import com.aibench.llm.LlmClientProvider

class CorpOpenAiProvider : LlmClientProvider {
    override val name = "corp-openai"
    override fun build(): LlmClient = CorpOpenAiClient(CorpOpenAiConfig.loadDefault())
}
