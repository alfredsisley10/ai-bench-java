package com.aibench.llm.copilot

import com.aibench.llm.LlmClient
import com.aibench.llm.LlmClientProvider

class CopilotProvider : LlmClientProvider {
    override val name = "copilot"
    override fun build(): LlmClient = CopilotSocketClient()
}
