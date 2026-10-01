package com.kflow.erp.technical.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FrameworkProblemHandlerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RequestFixture())
            .setControllerAdvice(new FrameworkProblemHandler()).build();

    @Test
    void malformedJsonReturnsProblemDetail() throws Exception {
        mvc.perform(post("/test/request").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:kflow:problem:invalid-request"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.instance").value("/test/request"));
    }

    @Test
    void validationReturnsFieldReasonsWithoutRejectedValues() throws Exception {
        mvc.perform(post("/test/request").contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("label"))
                .andExpect(jsonPath("$.errors[0].reason").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
    }

    @Test
    void programmingErrorsAreNotMappedToClientErrors() {
        assertThatThrownBy(() -> mvc.perform(post("/test/bug")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    // Test-only request plumbing: never scanned or shipped with the application.
    @RestController
    static class RequestFixture {
        record Input(@NotBlank String label) {}
        @PostMapping("/test/request")
        void validate(@Valid @RequestBody Input input) {}
        @PostMapping("/test/bug")
        void bug() { throw new IllegalArgumentException("Programming failure"); }
    }
}
