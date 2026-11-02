package com.aiexpense.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiexpense.support.AuthTestSupport;
import com.aiexpense.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Health endpoint and profile-dependent exposure: the default and "prod" profiles expose only
 * {"status":"UP"} and no Swagger; "dev" adds health details and Swagger. Other actuator endpoints
 * are not exposed: without a token they answer 401, and even WITH a valid token 404.
 */
class HealthAndProfilesTest {

    private static final String[] NOT_EXPOSED_ACTUATOR_ENDPOINTS = {
        "/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/info",
        "/actuator/loggers", "/actuator/heapdump", "/actuator/threaddump", "/actuator/mappings",
        "/actuator/metrics"
    };

    @Nested
    class DefaultProfile extends DatabaseIntegrationTest {

        @Autowired
        MockMvc mvc;

        @Test
        void healthIsUpWithoutDetails() throws Exception {
            mvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"status\":\"UP\"}", org.springframework.test.json.JsonCompareMode.STRICT));
        }

        @ParameterizedTest(name = "{0} is not exposed")
        @ValueSource(strings = {
            "/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/info",
            "/actuator/loggers", "/actuator/heapdump", "/actuator/threaddump", "/actuator/mappings",
            "/actuator/metrics"
        })
        void otherActuatorEndpointsAreNotExposed(String path) throws Exception {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            String token = AuthTestSupport.register(mvc, AuthTestSupport.uniqueEmail()).accessToken();
            mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        }

        @Test
        void swaggerIsDisabled() throws Exception {
            mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
            mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @ActiveProfiles("prod")
    class ProdProfile extends DatabaseIntegrationTest {

        @Autowired
        MockMvc mvc;

        @Test
        void healthIsUpWithoutDetails() throws Exception {
            mvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"status\":\"UP\"}", org.springframework.test.json.JsonCompareMode.STRICT));
        }

        @Test
        void swaggerIsDisabled() throws Exception {
            mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
            mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
        }

        @Test
        void otherActuatorEndpointsAreNotExposed() throws Exception {
            String token = AuthTestSupport.register(mvc, AuthTestSupport.uniqueEmail()).accessToken();
            for (String path : NOT_EXPOSED_ACTUATOR_ENDPOINTS) {
                mvc.perform(get(path)).andExpect(status().isUnauthorized());
                mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
            }
        }
    }

    @Nested
    @ActiveProfiles("dev")
    class DevProfile extends DatabaseIntegrationTest {

        @Autowired
        MockMvc mvc;

        @Test
        void healthShowsDatabaseComponent() throws Exception {
            mvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components.db.status").value("UP"))
                    .andExpect(jsonPath("$.components.db.details.database").value("PostgreSQL"));
        }

        @Test
        void swaggerIsEnabled() throws Exception {
            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.openapi").exists());
        }

        @Test
        void otherActuatorEndpointsAreStillNotExposed() throws Exception {
            String token = AuthTestSupport.register(mvc, AuthTestSupport.uniqueEmail()).accessToken();
            for (String path : NOT_EXPOSED_ACTUATOR_ENDPOINTS) {
                mvc.perform(get(path)).andExpect(status().isUnauthorized());
                mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
            }
        }
    }
}
