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
class DemoControllerTest {

    @Autowired
    lateinit var mvc: MockMvc

    @Test
    fun `demo page returns 200 and lists issues`() {
        mvc.get("/demo").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("BUG-0001")) }
            content { string(org.hamcrest.Matchers.containsString("Demo issues")) }
        }
    }

    @Test
    fun `demo detail for existing issue returns 200`() {
        mvc.get("/demo/BUG-0001").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("ACH same-day cutoff")) }
        }
    }

    @Test
    fun `demo detail for unknown issue shows not found`() {
        mvc.get("/demo/BUG-9999").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("not found")) }
        }
    }

    @Test
    fun `prepare issue redirects to demo`() {
        mvc.post("/demo/prepare/BUG-0001").andExpect {
            status { is3xxRedirection() }
            header { string("Location", "/demo") }
        }
    }
}
