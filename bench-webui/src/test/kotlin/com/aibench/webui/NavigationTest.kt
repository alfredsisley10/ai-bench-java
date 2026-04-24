package com.aibench.webui

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class NavigationTest {

    @Autowired
    lateinit var mvc: MockMvc

    @Test
    fun `all main pages return 200`() {
        listOf("/", "/demo", "/results", "/github", "/jira", "/llm", "/run", "/copilot-guide").forEach { path ->
            mvc.get(path).andExpect {
                status { isOk() }
            }
        }
    }

    @Test
    fun `actuator health returns 200`() {
        mvc.get("/actuator/health").andExpect {
            status { isOk() }
        }
    }
}
