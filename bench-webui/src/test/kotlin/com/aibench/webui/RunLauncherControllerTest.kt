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
    fun `run form returns 200 with the launcher template`() {
        // The launcher renders for any operator regardless of provider
        // verification state -- the dropdowns gate themselves based on
        // what RegisteredModelsRegistry sees as verified at request time.
        // (The previous version of this test expected a "no providers
        // verified yet" string, but availableModels() auto-discovers
        // a Copilot bridge by probing the TCP port, so on a developer
        // box where the bridge happens to be live the warning is
        // correctly suppressed and the test was flaking.)
        mvc.get("/run").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("Launch benchmark run")) }
        }
    }

    @Test
    fun `launch with a clearly-fake provider redirects back with error=unverified`() {
        // Defense-in-depth: even if a client somehow POSTs a provider
        // string, the controller re-checks against
        // RegisteredModelsRegistry.available and bounces unverified
        // launches with ?error=unverified rather than starting a run.
        // We deliberately use a sentinel provider/model that no
        // RegisteredModelsRegistry instance ever returns so the test
        // is independent of which real bridges happen to be live in
        // the test environment.
        mvc.post("/run/launch") {
            param("targetType", "omnibank")
            param("bugId", "BUG-0001")
            param("provider", "acme-fake-provider")
            param("modelId", "acme-fake-model-id")
            param("appmapMode", "OFF")
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/run?error=unverified")
        }
    }
}
