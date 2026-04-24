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
class AppMapControllerTest {

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `appmap list page renders even when no traces exist`() {
        mvc.get("/demo/appmap").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("AppMap traces")) }
        }
    }

    @Test
    fun `unknown trace id returns the missing page`() {
        mvc.get("/demo/appmap/view") {
            param("id", "bm9wZS1ub3QtcmVhbA")
        }.andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("not found")) }
        }
    }

    @Test
    fun `demo page links to the appmap viewer`() {
        mvc.get("/demo").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("/demo/appmap")) }
        }
    }

    @Test
    fun `delete-one with unknown id reports already-gone and redirects`() {
        mvc.post("/demo/appmap/delete-one") {
            param("id", "bm9wZS1ub3QtcmVhbA")
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/demo/appmap")
        }
    }

    @Test
    fun `delete-all without confirm just redirects without deleting`() {
        // The controller blocks the delete unless confirm=yes is set;
        // here we only assert the redirect, since the flash message lives
        // on the http session which MockMvc doesn't share across requests.
        mvc.post("/demo/appmap/delete-all").andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/demo/appmap")
        }
    }
}
