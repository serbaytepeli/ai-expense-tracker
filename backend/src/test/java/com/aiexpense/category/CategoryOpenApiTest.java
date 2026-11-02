package com.aiexpense.category;

import static com.aiexpense.support.AuthTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiexpense.support.DatabaseIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * The generated mobile types come from this document: response fields that are always sent must
 * be required, otherwise the client sees them as optional (T0.6 contract check).
 */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class CategoryOpenApiTest extends DatabaseIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void categoryResponseFieldsAreRequiredAndSystemKeyIsNullable() throws Exception {
        JsonNode schema = json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn())
                .path("components").path("schemas").path("CategoryResponse");

        List<String> required = new ArrayList<>();
        schema.path("required").forEach(field -> required.add(field.asString()));
        assertThat(required).containsExactlyInAnyOrder(
                "id", "name", "icon", "color", "systemKey", "sortOrder", "archived");

        List<String> systemKeyTypes = new ArrayList<>();
        schema.path("properties").path("systemKey").path("type").forEach(t -> systemKeyTypes.add(t.asString()));
        assertThat(systemKeyTypes).containsExactlyInAnyOrder("string", "null");
    }
}
