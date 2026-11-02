package com.aiexpense.openapi;

import static com.aiexpense.support.AuthTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiexpense.support.DatabaseIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * The mobile API types are generated from this document: response fields that are always sent
 * must be required, otherwise the generated types make them optional.
 */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class ResponseSchemasTest extends DatabaseIntegrationTest {

    /** Response schema -> fields that are always present (nullable ones included). */
    static final Map<String, List<String>> REQUIRED = Map.of(
            "AuthResponse", List.of("accessToken", "refreshToken", "expiresIn", "user"),
            "UserResponse", List.of("id", "email", "displayName", "currency", "timeZone", "locale"),
            "TokenResponse", List.of("accessToken", "refreshToken", "expiresIn"),
            "ExpenseResponse", List.of("id", "amount", "currency", "date", "category", "description", "note",
                    "paymentMethod", "createdAt", "updatedAt"),
            "ExpenseCategory", List.of("id", "name", "icon", "color", "archived"),
            "ExpensePageResponse", List.of("items", "page", "size", "totalItems", "totalPages"));

    @Autowired
    MockMvc mvc;

    @Test
    void responseFieldsThatAreAlwaysSentAreRequired() throws Exception {
        JsonNode schemas = schemas();
        REQUIRED.forEach((schema, fields) -> {
            List<String> required = new ArrayList<>();
            schemas.path(schema).path("required").forEach(f -> required.add(f.asString()));
            assertThat(required).as(schema).containsExactlyInAnyOrderElementsOf(fields);
        });
    }

    @Test
    void optionalExpenseFieldsAreNullableAndAmountIsAString() throws Exception {
        JsonNode properties = schemas().path("ExpenseResponse").path("properties");
        for (String field : List.of("description", "note", "paymentMethod")) {
            List<String> types = new ArrayList<>();
            properties.path(field).path("type").forEach(t -> types.add(t.asString()));
            assertThat(types).as(field).contains("null");
        }
        assertThat(properties.path("amount").path("type").asString()).isEqualTo("string");
        assertThat(schemas().path("ExpenseRequest").path("properties").path("amount").path("type").asString())
                .isEqualTo("string");
    }

    private JsonNode schemas() throws Exception {
        return json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn())
                .path("components").path("schemas");
    }
}
