package com.aiexpense.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiexpense.support.DatabaseIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Writes the OpenAPI document to build/openapi.json. CI (T0.6) generates the mobile API types
 * from this file. Run alone with: ./gradlew exportOpenApi
 */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class OpenApiExportTest extends DatabaseIntegrationTest {

    static final Path OUTPUT = Path.of("build", "openapi.json");

    @Autowired
    MockMvc mvc;

    @Test
    void exportsOpenApiDocument() throws Exception {
        String json = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, json, StandardCharsets.UTF_8);

        assertThat(json).contains("\"openapi\"");
        assertThat(OUTPUT).exists();
    }
}
