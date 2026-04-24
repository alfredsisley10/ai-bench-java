package com.aibench.webui

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class LlmConfigControllerTest {

    @Autowired
    lateinit var mvc: MockMvc

    @Test
    fun `llm config page returns 200`() {
        mvc.get("/llm").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("LLM providers")) }
        }
    }

    @Test
    fun `enumerate copilot models returns JSON`() {
        mvc.get("/llm/providers/copilot/enumerate").andExpect {
            status { isOk() }
            jsonPath("$.provider") { value("copilot") }
            jsonPath("$.models") { isArray() }
        }
    }

    @Test
    fun `enumerate corp-openai models returns JSON`() {
        mvc.get("/llm/providers/corp-openai/enumerate").andExpect {
            status { isOk() }
            jsonPath("$.provider") { value("corp-openai") }
            jsonPath("$.models.length()") { value(5) }
        }
    }

    @Test
    fun `enumerate unknown provider returns error`() {
        mvc.get("/llm/providers/unknown/enumerate").andExpect {
            status { isOk() }
            jsonPath("$.error") { exists() }
        }
    }
}
