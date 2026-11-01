package com.aiexpense.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiexpense.common.RequestIds;
import com.aiexpense.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** RFC 9457 error format and X-Request-Id behaviour (docs/api-contract.md section 1). */
@Import(ErrorResponsesTest.FailingController.class)
class ErrorResponsesTest extends DatabaseIntegrationTest {

    static final String SECRET_DETAIL = "internal detail that must not leak";

    @TestConfiguration
    @RestController
    static class FailingController {
        @GetMapping("/test-only/boom")
        String boom() {
            throw new IllegalStateException(SECRET_DETAIL);
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void unknownPathIsProblemJson404() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.type").value("https://aiexpense.app/problems/not-found"))
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.detail").value("The requested resource was not found."))
                .andReturn();

        String headerId = result.getResponse().getHeader(RequestIds.HEADER);
        assertThat(headerId).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).contains("\"requestId\":\"" + headerId + "\"");
    }

    @Test
    void wrongMethodIsProblemJson405() throws Exception {
        mvc.perform(delete("/actuator/health"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unexpectedErrorIsGeneric500WithoutInternals() throws Exception {
        MvcResult result = mvc.perform(get("/test-only/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.requestId").exists())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(SECRET_DETAIL)
                .doesNotContain("IllegalStateException")
                .doesNotContain("trace");
    }

    @Test
    void wellFormedIncomingRequestIdIsEchoed() throws Exception {
        mvc.perform(get("/actuator/health").header(RequestIds.HEADER, "mobile-1234"))
                .andExpect(header().string(RequestIds.HEADER, "mobile-1234"));
    }

    @Test
    void everyResponseHasARequestId() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(header().exists(RequestIds.HEADER));
    }
}
