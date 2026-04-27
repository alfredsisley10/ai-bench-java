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
class JiraConfigControllerTest {

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `jira config page is reachable`() {
        mvc.get("/jira").andExpect { status { isOk() } }
    }

    @Test
    fun `save persists trimmed base url and redirects`() {
        mvc.post("/jira/save") {
            param("baseUrl", "https://jira.example.com/")
            param("email", "user@example.com")
            param("authMethod", "api-token")
            param("apiToken", "secret")
            param("credentialSource", "keystore")
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/jira")
        }
    }

    @Test
    fun `test connection without configured url reports failure flash and redirects`() {
        mvc.post("/jira/test").andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/jira")
        }
    }
}
