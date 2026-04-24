package com.aibench.webui

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class DashboardControllerTest {

    @Autowired
    lateinit var mvc: MockMvc

    @Test
    fun `dashboard returns 200 and contains summary cards`() {
        mvc.get("/").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("Summary")) }
            content { string(org.hamcrest.Matchers.containsString("Runs")) }
        }
    }

    @Test
    fun `results page returns 200`() {
        mvc.get("/results").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("Benchmark Results")) }
        }
    }
}
