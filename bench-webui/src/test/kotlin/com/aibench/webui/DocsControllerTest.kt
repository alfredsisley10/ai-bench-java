package com.aibench.webui

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class DocsControllerTest {

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `operations guide renders`() {
        mvc.get("/docs/operations-guide").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("Logging in")) }
            content { string(org.hamcrest.Matchers.containsString("X-Forwarded-Email")) }
            content { string(org.hamcrest.Matchers.containsString("Driving the demo banking app")) }
        }
    }

    @Test
    fun `demo page surfaces login + ops guide help text`() {
        mvc.get("/demo").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("/docs/operations-guide")) }
            content { string(org.hamcrest.Matchers.containsString("Need access?")) }
        }
    }
}
