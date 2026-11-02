package com.aiexpense.category;

import static com.aiexpense.support.AuthTestSupport.PASSWORD;
import static com.aiexpense.support.AuthTestSupport.postJson;
import static com.aiexpense.support.AuthTestSupport.registerJson;
import static com.aiexpense.support.AuthTestSupport.uniqueClientIp;
import static com.aiexpense.support.AuthTestSupport.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiexpense.support.AuthTestSupport;
import com.aiexpense.support.DatabaseIntegrationTest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** The user and the built-in categories are created in one transaction (FR-3, T1.3). */
@Import({AuthTestSupport.Config.class, RegistrationRollbackTest.FailingDefaults.class})
class RegistrationRollbackTest extends DatabaseIntegrationTest {

    static final AtomicReference<UUID> ATTEMPTED_USER = new AtomicReference<>();

    /**
     * Replaces the CategoryApi seen by registration: it inserts the real default categories,
     * then fails, so everything written so far in the registration must be rolled back.
     */
    @TestConfiguration
    static class FailingDefaults {
        @Bean
        @Primary
        CategoryApi failingCategoryApi(@Qualifier("categoryService") CategoryApi real) {
            return new CategoryApi() {
                @Override
                public void createDefaultCategories(UUID userId) {
                    real.createDefaultCategories(userId);
                    ATTEMPTED_USER.set(userId);
                    throw new IllegalStateException("simulated failure after inserting default categories");
                }

                @Override
                public java.util.Optional<CategorySummary> findForUser(UUID userId, UUID categoryId) {
                    return real.findForUser(userId, categoryId);
                }

                @Override
                public java.util.List<CategorySummary> listForUser(UUID userId) {
                    return real.listForUser(userId);
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void failureWhileCreatingDefaultCategoriesRollsBackTheWholeRegistration() throws Exception {
        ATTEMPTED_USER.set(null);
        String email = uniqueEmail();

        mvc.perform(postJson("/api/v1/auth/register", registerJson(email, PASSWORD, "Test", null))
                        .with(uniqueClientIp()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        UUID userId = ATTEMPTED_USER.get();
        assertThat(userId).as("the default categories were inserted before the failure").isNotNull();
        assertThat(count("SELECT count(*) FROM users WHERE email = :v", email)).isZero();
        assertThat(count("SELECT count(*) FROM users WHERE id = :v", userId)).isZero();
        assertThat(count("SELECT count(*) FROM categories WHERE user_id = :v", userId)).isZero();
        assertThat(count("SELECT count(*) FROM auth_sessions WHERE user_id = :v", userId)).isZero();
    }

    private int count(String sql, Object value) {
        return jdbc.sql(sql).param("v", value).query(Integer.class).single();
    }
}
