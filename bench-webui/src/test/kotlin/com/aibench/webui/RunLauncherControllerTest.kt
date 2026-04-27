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
    fun `run form returns 200 and surfaces unverified-providers warning`() {
        // The launcher gates the provider/model dropdowns to the set
        // RegisteredModelsRegistry.available has confirmed via the /llm
        // verify step. With no provider registered (the default test
        // fixture), the form still renders but shows a warning banner
        // and disables the dropdowns.
        mvc.get("/run").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("No LLM providers verified yet")) }
        }
    }

    @Test
    fun `launch with unverified provider redirects back to form with error`() {
        // Defense-in-depth: even if a client somehow POSTs a provider
        // string, the controller re-checks against
        // RegisteredModelsRegistry.available and bounces unverified
        // launches with ?error=unverified rather than starting a run.
        mvc.post("/run/launch") {
            param("targetType", "omnibank")
            param("bugId", "BUG-0001")
            param("provider", "copilot")
            param("modelId", "copilot-default")
            param("appmapMode", "with")
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/run?error=unverified")
        }
    }
}
