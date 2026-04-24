package com.aibench.webui

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class ResultsControllerTest {

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `results list page is reachable`() {
        mvc.get("/results").andExpect { status { isOk() } }
    }

    @Test
    fun `result detail page renders given an arbitrary run id`() {
        mvc.get("/results/run-12345").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("run-12345")) }
        }
    }
}
