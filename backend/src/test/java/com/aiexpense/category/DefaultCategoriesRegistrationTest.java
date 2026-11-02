package com.aiexpense.category;

import static com.aiexpense.category.CategoryTestSupport.list;
import static com.aiexpense.support.AuthTestSupport.json;
import static com.aiexpense.support.AuthTestSupport.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiexpense.support.AuthTestSupport;
import com.aiexpense.support.AuthTestSupport.Tokens;
import com.aiexpense.support.DatabaseIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/** Registration creates the approved built-in categories, exactly once per user (FR-3). */
@Import(AuthTestSupport.Config.class)
class DefaultCategoriesRegistrationTest extends DatabaseIntegrationTest {

    /** sortOrder, name, systemKey, icon, color: the list approved for T1.3. */
    static final List<List<String>> EXPECTED = List.of(
            List.of("1", "Market", "GROCERIES", "cart-outline", "#2E7D32"),
            List.of("2", "Yeme-İçme", "FOOD_DRINK", "restaurant-outline", "#EF6C00"),
            List.of("3", "Ulaşım", "TRANSPORT", "bus-outline", "#1565C0"),
            List.of("4", "Faturalar", "BILLS", "receipt-outline", "#6A1B9A"),
            List.of("5", "Kira/Konut", "HOUSING", "home-outline", "#4E342E"),
            List.of("6", "Sağlık", "HEALTH", "medkit-outline", "#C62828"),
            List.of("7", "Eğlence", "ENTERTAINMENT", "film-outline", "#AD1457"),
            List.of("8", "Giyim", "CLOTHING", "shirt-outline", "#00838F"),
            List.of("9", "Eğitim", "EDUCATION", "school-outline", "#283593"),
            List.of("10", "Abonelikler", "SUBSCRIPTIONS", "repeat-outline", "#F9A825"),
            List.of("11", "Diğer", "OTHER", "ellipsis-horizontal-outline", "#616161"));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void registrationCreatesTheBuiltInCategoriesInOrder() throws Exception {
        Tokens user = AuthTestSupport.register(mvc, uniqueEmail());

        JsonNode body = json(mvc.perform(list(user.accessToken())).andExpect(status().isOk()).andReturn());

        List<List<String>> actual = new ArrayList<>();
        for (JsonNode category : body) {
            actual.add(List.of(category.get("sortOrder").asString(), category.get("name").asString(),
                    category.get("systemKey").asString(), category.get("icon").asString(),
                    category.get("color").asString()));
            assertThat(category.get("archived").asBoolean()).isFalse();
            assertThat(category.get("id").asString()).isNotBlank();
        }
        assertThat(actual).isEqualTo(EXPECTED);
    }

    @Test
    void eachUserGetsExactlyOneSetOfBuiltInCategories() throws Exception {
        Tokens first = AuthTestSupport.register(mvc, uniqueEmail());
        Tokens second = AuthTestSupport.register(mvc, uniqueEmail());

        for (Tokens user : List.of(first, second)) {
            Integer total = jdbc.sql("SELECT count(*) FROM categories WHERE user_id = :u")
                    .param("u", UUID.fromString(user.userId())).query(Integer.class).single();
            Integer distinctKeys = jdbc.sql("SELECT count(DISTINCT system_key) FROM categories WHERE user_id = :u")
                    .param("u", UUID.fromString(user.userId())).query(Integer.class).single();
            assertThat(total).isEqualTo(EXPECTED.size());
            assertThat(distinctKeys).isEqualTo(EXPECTED.size());
        }
    }

    @Test
    void timestampsComeFromTheApplicationClock() throws Exception {
        Tokens user = AuthTestSupport.register(mvc, uniqueEmail());

        Integer wrong = jdbc.sql("""
                SELECT count(*) FROM categories
                WHERE user_id = :u AND (created_at <> :t OR updated_at <> :t OR archived_at IS NOT NULL)""")
                .param("u", UUID.fromString(user.userId()))
                .param("t", AuthTestSupport.START.atOffset(java.time.ZoneOffset.UTC))
                .query(Integer.class).single();
        assertThat(wrong).isZero();
    }
}
