package com.aibench.webui

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
class DemoApiControllerTest {

    @Autowired
    lateinit var mvc: MockMvc

    @Test
    fun `list issues returns JSON array with 12 items`() {
        mvc.get("/api/demo/issues").andExpect {
            status { isOk() }
            content { contentType(MediaType.APPLICATION_JSON) }
            jsonPath("$.length()") { value(12) }
            jsonPath("$[0].id") { value("BUG-0001") }
        }
    }

    @Test
    fun `get issue by id returns correct issue`() {
        mvc.get("/api/demo/issues/BUG-0003").andExpect {
            status { isOk() }
            jsonPath("$.id") { value("BUG-0003") }
            jsonPath("$.module") { value("ledger-core") }
            jsonPath("$.difficulty") { value("HARD") }
        }
    }

    @Test
    fun `get unknown issue returns 404`() {
        mvc.get("/api/demo/issues/BUG-9999").andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `prepare issue returns prepared status`() {
        mvc.post("/api/demo/issues/BUG-0001/prepare").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("prepared") }
            jsonPath("$.issueId") { value("BUG-0001") }
        }
    }

    @Test
    fun `run benchmark returns accepted`() {
        mvc.post("/api/demo/issues/BUG-0001/run") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"provider":"copilot","modelId":"copilot-default","seeds":1}"""
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.status") { value("queued") }
            jsonPath("$.provider") { value("copilot") }
        }
    }

    @Test
    fun `reset issue returns reset status`() {
        mvc.post("/api/demo/issues/BUG-0001/reset").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("reset") }
        }
    }

    @Test
    fun `banking app status returns path and module info`() {
        mvc.get("/api/demo/banking-app/status").andExpect {
            status { isOk() }
            jsonPath("$.path") { exists() }
            jsonPath("$.exists") { exists() }
        }
    }
}
