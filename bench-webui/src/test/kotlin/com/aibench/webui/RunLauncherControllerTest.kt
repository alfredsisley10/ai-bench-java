package com.aibench.webui

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
class RunLauncherControllerTest {

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `run form returns 200 and exposes default models`() {
        mvc.get("/run").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("Copilot (default)")) }
            content { string(org.hamcrest.Matchers.containsString("Corporate OpenAI (default)")) }
        }
    }

    @Test
    fun `launch redirects to results page`() {
        mvc.post("/run/launch") {
            param("targetType", "omnibank")
            param("bugId", "BUG-0001")
            param("provider", "copilot")
            param("modelId", "copilot-default")
            param("appmapMode", "with")
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/results")
        }
    }
}
