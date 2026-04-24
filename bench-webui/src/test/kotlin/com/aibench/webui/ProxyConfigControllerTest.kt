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
class ProxyConfigControllerTest {

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `proxy page loads successfully`() {
        mvc.get("/proxy").andExpect { status { isOk() } }
    }

    @Test
    fun `api proxy returns json with auto-detected source by default`() {
        mvc.get("/api/proxy").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/json") }
        }
    }

    @Test
    fun `reset endpoint clears session and redirects`() {
        mvc.post("/proxy/reset").andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/proxy")
        }
    }
}
