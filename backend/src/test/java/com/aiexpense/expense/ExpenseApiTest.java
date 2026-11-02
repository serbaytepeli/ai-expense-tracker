package com.aiexpense.expense;

import static com.aiexpense.expense.ExpenseTestSupport.BASE;
import static com.aiexpense.expense.ExpenseTestSupport.body;
import static com.aiexpense.expense.ExpenseTestSupport.categoryId;
import static com.aiexpense.expense.ExpenseTestSupport.create;
import static com.aiexpense.expense.ExpenseTestSupport.createRaw;
import static com.aiexpense.expense.ExpenseTestSupport.fetch;
import static com.aiexpense.expense.ExpenseTestSupport.list;
import static com.aiexpense.expense.ExpenseTestSupport.remove;
import static com.aiexpense.expense.ExpenseTestSupport.replace;
import static com.aiexpense.support.AuthTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiexpense.support.AuthTestSupport;
import com.aiexpense.support.AuthTestSupport.Tokens;
import com.aiexpense.support.DatabaseIntegrationTest;
import com.aiexpense.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Expense API (docs/api-contract.md section 5, FR-2). The test clock is 2026-11-01 in Istanbul. */
@Import(AuthTestSupport.Config.class)
class ExpenseApiTest extends DatabaseIntegrationTest {

    static final String TODAY = "2026-11-01";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    Tokens user;
    String market;

    @BeforeEach
    void registerUser() throws Exception {
        clock.set(AuthTestSupport.START);
        user = ExpenseTestSupport.register(mvc, null);
        market = categoryId(mvc, token(), "GROCERIES");
    }

    // --- create / get ---------------------------------------------------------------------------

    @Test
    void createReturnsTheExpenseWithLocationAndCategory() throws Exception {
        ObjectNode request = body("149.90", "2026-10-28", market);
        request.put("description", "  Haftalık market  ");
        request.put("note", "Kart ile");
        request.put("paymentMethod", "CARD");

        var result = perform(create(token(), request))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.amount").value("149.90"))
                .andExpect(jsonPath("$.currency").value("TRY"))
                .andExpect(jsonPath("$.date").value("2026-10-28"))
                .andExpect(jsonPath("$.category.id").value(market))
                .andExpect(jsonPath("$.category.name").value("Market"))
                .andExpect(jsonPath("$.category.icon").value("cart-outline"))
                .andExpect(jsonPath("$.category.color").value("#2E7D32"))
                .andExpect(jsonPath("$.category.archived").value(false))
                .andExpect(jsonPath("$.description").value("Haftalık market"))
                .andExpect(jsonPath("$.note").value("Kart ile"))
                .andExpect(jsonPath("$.paymentMethod").value("CARD"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-01T10:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-11-01T10:00:00Z"))
                .andReturn();
        String id = json(result).get("id").asString();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(BASE + "/" + id);
        perform(fetch(token(), id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.amount").value("149.90"))
                .andExpect(jsonPath("$.description").value("Haftalık market"));
    }

    @Test
    void optionalFieldsAreNullWhenMissingOrBlank() throws Exception {
        ObjectNode request = body("5", TODAY, market);
        request.put("description", "   ");
        perform(create(token(), request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("5.00"))
                .andExpect(jsonPath("$.description").isEmpty())
                .andExpect(jsonPath("$.note").isEmpty())
                .andExpect(jsonPath("$.paymentMethod").isEmpty());
        JsonNode stored = json(perform(list(token())).andReturn()).get("items").get(0);
        assertThat(stored.has("description") && stored.get("description").isNull()).isTrue();
        assertThat(stored.has("note") && stored.get("note").isNull()).isTrue();
        assertThat(stored.has("paymentMethod") && stored.get("paymentMethod").isNull()).isTrue();
    }

    // --- money (database-design.md section 2, T2.1 AC) -------------------------------------------

    @Test
    void invalidAmountsAreRejectedWithoutRounding() throws Exception {
        for (String amount : new String[] {"0", "0.00", "-1", "+1", "1.234", "1e3", "1,5", " 1", "1 ",
                "1000000000000.00", "", ".5", "5.", "abc", "١٢"}) {
            expectValidationError(create(token(), body(amount, TODAY, market)), "amount");
        }
        // Numbers instead of strings would go through binary floating point: rejected.
        perform(createRaw(token(), "{\"amount\":149.90,\"date\":\"" + TODAY + "\",\"categoryId\":\"" + market + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        expectValidationError(createRaw(token(), "{\"date\":\"" + TODAY + "\",\"categoryId\":\"" + market + "\"}"),
                "amount");
        assertThat(count()).isZero();
    }

    @Test
    void validAmountsAreNormalizedToTwoDecimals() throws Exception {
        String[][] cases = {{"5", "5.00"}, {"5.5", "5.50"}, {"0.01", "0.01"}, {"999999999999.99", "999999999999.99"},
                {"007.10", "7.10"}};
        for (String[] c : cases) {
            perform(create(token(), body(c[0], TODAY, market)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.amount").value(c[1]));
        }
        assertThat(jdbc.sql("SELECT amount::text FROM expenses WHERE user_id = :u ORDER BY amount DESC LIMIT 1")
                .param("u", UUID.fromString(user.userId())).query(String.class).single())
                .isEqualTo("999999999999.99");
    }

    // --- other validation --------------------------------------------------------------------

    @Test
    void invalidFieldsAreRejected() throws Exception {
        expectValidationError(createRaw(token(), "{\"amount\":\"1\",\"categoryId\":\"" + market + "\"}"), "date");
        expectValidationError(createRaw(token(), "{\"amount\":\"1\",\"date\":\"" + TODAY + "\"}"), "categoryId");
        expectValidationError(create(token(), body("1", "2026-11-02", market)), "date");

        ObjectNode longDescription = body("1", TODAY, market);
        longDescription.put("description", "a".repeat(141));
        expectValidationError(create(token(), longDescription), "description");
        ObjectNode longNote = body("1", TODAY, market);
        longNote.put("note", "ğ".repeat(501));
        expectValidationError(create(token(), longNote), "note");

        for (String json : new String[] {
                "{\"amount\":\"1\",\"date\":\"2026-13-01\",\"categoryId\":\"" + market + "\"}",
                "{\"amount\":\"1\",\"date\":\"01.11.2026\",\"categoryId\":\"" + market + "\"}",
                "{\"amount\":\"1\",\"date\":\"" + TODAY + "\",\"categoryId\":\"not-a-uuid\"}",
                "{\"amount\":\"1\",\"date\":\"" + TODAY + "\",\"categoryId\":\"" + market + "\",\"paymentMethod\":\"BITCOIN\"}",
                "not json"}) {
            perform(createRaw(token(), json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        assertThat(count()).isZero();
    }

    @Test
    void maximumTextLengthsAreAcceptedAndCountedInCharacters() throws Exception {
        ObjectNode request = body("1", TODAY, market);
        request.put("description", "ş".repeat(140));
        request.put("note", "😀".repeat(500));
        perform(create(token(), request)).andExpect(status().isCreated());
    }

    @Test
    void unknownCategoryIsNotFound() throws Exception {
        perform(create(token(), body("1", TODAY, UUID.randomUUID().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(count()).isZero();
    }

    // --- update ---------------------------------------------------------------------------

    @Test
    void putReplacesAllEditableFields() throws Exception {
        ObjectNode original = body("10.00", "2026-10-01", market);
        original.put("description", "Eski");
        original.put("note", "not");
        original.put("paymentMethod", "CASH");
        String id = createExpense(original);
        String transport = categoryId(mvc, token(), "TRANSPORT");
        clock.advance(Duration.ofMinutes(5));

        ObjectNode replacement = body("25.5", "2026-10-31", transport);
        replacement.put("description", "Taksi");
        perform(replace(token(), id, replacement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.amount").value("25.50"))
                .andExpect(jsonPath("$.date").value("2026-10-31"))
                .andExpect(jsonPath("$.category.id").value(transport))
                .andExpect(jsonPath("$.description").value("Taksi"))
                .andExpect(jsonPath("$.note").isEmpty())
                .andExpect(jsonPath("$.paymentMethod").isEmpty())
                .andExpect(jsonPath("$.createdAt").value("2026-11-01T10:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-11-01T10:05:00Z"));

        perform(fetch(token(), id))
                .andExpect(jsonPath("$.amount").value("25.50"))
                .andExpect(jsonPath("$.category.id").value(transport))
                .andExpect(jsonPath("$.note").isEmpty());
    }

    @Test
    void putUsesTheSameValidationAsCreate() throws Exception {
        String id = createExpense(body("10", TODAY, market));
        expectValidationError(replace(token(), id, body("0", TODAY, market)), "amount");
        expectValidationError(replace(token(), id, body("1.001", TODAY, market)), "amount");
        expectValidationError(replace(token(), id, body("1", "2026-11-02", market)), "date");
        perform(replace(token(), id, body("1", TODAY, UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
        perform(fetch(token(), id)).andExpect(jsonPath("$.amount").value("10.00"));
    }

    // --- archived categories ------------------------------------------------------------------

    @Test
    void archivedCategoryCannotBeUsedForNewExpenses() throws Exception {
        archive(market);
        perform(create(token(), body("1", TODAY, market)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CATEGORY_ARCHIVED"));
        assertThat(count()).isZero();
    }

    @Test
    void expenseInAnArchivedCategoryStaysVisibleAndEditable() throws Exception {
        String id = createExpense(body("10", TODAY, market));
        archive(market);

        perform(fetch(token(), id)).andExpect(jsonPath("$.category.archived").value(true));
        perform(list(token())).andExpect(jsonPath("$.items[0].category.archived").value(true));
        // Same (archived) category: editing is allowed.
        perform(replace(token(), id, body("12", TODAY, market)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value("12.00"));
        // Moving to another archived category is not.
        String transport = categoryId(mvc, token(), "TRANSPORT");
        archive(transport);
        perform(replace(token(), id, body("12", TODAY, transport)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_ARCHIVED"));
        // Moving to an active category is.
        String bills = categoryId(mvc, token(), "BILLS");
        perform(replace(token(), id, body("12", TODAY, bills)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category.id").value(bills));
        // ...and there is no way back into the archived one.
        perform(replace(token(), id, body("12", TODAY, market)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_ARCHIVED"));
    }

    // --- delete ---------------------------------------------------------------------------------

    @Test
    void deletedExpenseIsGoneEverywhere() throws Exception {
        String id = createExpense(body("10", TODAY, market));
        String kept = createExpense(body("20", TODAY, market));

        perform(remove(token(), id)).andExpect(status().isNoContent());

        perform(fetch(token(), id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        perform(replace(token(), id, body("1", TODAY, market))).andExpect(status().isNotFound());
        perform(remove(token(), id)).andExpect(status().isNotFound());
        perform(list(token()))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(kept));
        // Hard delete: the row itself is gone.
        assertThat(jdbc.sql("SELECT count(*) FROM expenses WHERE id = :id").param("id", UUID.fromString(id))
                .query(Integer.class).single()).isZero();
    }

    @Test
    void unknownOrMalformedIdsAreNotFound() throws Exception {
        for (String id : new String[] {UUID.randomUUID().toString(), "not-a-uuid"}) {
            perform(fetch(token(), id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
            perform(replace(token(), id, body("1", TODAY, market))).andExpect(status().isNotFound());
            perform(remove(token(), id)).andExpect(status().isNotFound());
        }
    }

    // --- list: order, paging, filters ------------------------------------------------------------

    @Test
    void listIsNewestFirstByDateThenCreationTime() throws Exception {
        String older = createExpense(body("1", "2026-10-15", market));
        clock.advance(Duration.ofSeconds(1));
        String sameDayFirst = createExpense(body("2", "2026-10-20", market));
        clock.advance(Duration.ofSeconds(1));
        String sameDaySecond = createExpense(body("3", "2026-10-20", market));
        clock.advance(Duration.ofSeconds(1));
        String newest = createExpense(body("4", TODAY, market));

        assertThat(ids(json(perform(list(token())).andReturn())))
                .containsExactly(newest, sameDaySecond, sameDayFirst, older);
    }

    @Test
    void listIsPaged() throws Exception {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            clock.advance(Duration.ofSeconds(1));
            created.add(0, createExpense(body(String.valueOf(i + 1), TODAY, market)));
        }

        JsonNode first = json(perform(list(token()).param("size", "10")).andExpect(status().isOk()).andReturn());
        assertThat(first.get("page").asInt()).isZero();
        assertThat(first.get("size").asInt()).isEqualTo(10);
        assertThat(first.get("totalItems").asLong()).isEqualTo(25);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);

        List<String> all = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            all.addAll(ids(json(perform(list(token()).param("page", String.valueOf(page)).param("size", "10"))
                    .andReturn())));
        }
        assertThat(all).containsExactlyElementsOf(created);
        perform(list(token()).param("page", "3").param("size", "10"))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalItems").value(25));
        // Default page size is 20.
        perform(list(token())).andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.items.length()").value(20));
    }

    @Test
    void invalidListParametersAreRejected() throws Exception {
        String[][] cases = {
                {"page", "-1"}, {"page", "x"}, {"size", "0"}, {"size", "101"}, {"size", "1.5"},
                {"month", "2026-13"}, {"month", "2026/10"}, {"from", "2026-02-30"}, {"to", "yesterday"},
                {"categoryId", "nope"}, {"q", "a"}, {"q", " b "}};
        for (String[] c : cases) {
            MockHttpServletRequestBuilder request = list(token()).param(c[0], c[1]);
            if (c[0].equals("from")) {
                request.param("to", TODAY);
            } else if (c[0].equals("to")) {
                request.param("from", TODAY);
            }
            expectValidationError(request, c[0]);
        }
        expectValidationError(list(token()).param("month", "2026-10").param("from", "2026-10-01").param("to", TODAY), "month");
        expectValidationError(list(token()).param("from", "2026-10-01"), "to");
        expectValidationError(list(token()).param("to", "2026-10-01"), "from");
        expectValidationError(list(token()).param("from", "2026-10-02").param("to", "2026-10-01"), "from");
    }

    @Test
    void listCanBeFilteredByMonthOrDateRange() throws Exception {
        String sept30 = createExpense(body("1", "2026-09-30", market));
        String oct1 = createExpense(body("2", "2026-10-01", market));
        String oct31 = createExpense(body("3", "2026-10-31", market));
        String nov1 = createExpense(body("4", TODAY, market));

        assertThat(ids(json(perform(list(token()).param("month", "2026-10")).andReturn())))
                .containsExactly(oct31, oct1);
        assertThat(ids(json(perform(list(token()).param("from", "2026-09-30").param("to", "2026-10-01"))
                .andReturn()))).containsExactly(oct1, sept30);
        assertThat(ids(json(perform(list(token()).param("from", TODAY).param("to", TODAY)).andReturn())))
                .containsExactly(nov1);
        perform(list(token()).param("month", "2026-10")).andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    void listCanBeFilteredByCategory() throws Exception {
        String transport = categoryId(mvc, token(), "TRANSPORT");
        String inMarket = createExpense(body("1", TODAY, market));
        String inTransport = createExpense(body("2", TODAY, transport));

        assertThat(ids(json(perform(list(token()).param("categoryId", market)).andReturn()))).containsExactly(inMarket);
        assertThat(ids(json(perform(list(token()).param("categoryId", transport).param("month", "2026-11"))
                .andReturn()))).containsExactly(inTransport);
        perform(list(token()).param("categoryId", UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void searchMatchesTheDescriptionIgnoringTurkishCase() throws Exception {
        String a = createWithDescription("Haftalık MARKET alışverişi");
        String b = createWithDescription("İSTANBUL kart dolumu");
        String c = createWithDescription("indirim %50_kampanya");
        ObjectNode noteOnly = body("1", TODAY, market);
        noteOnly.put("note", "market");
        createExpense(noteOnly);

        assertThat(ids(search("market"))).containsExactly(a);
        assertThat(ids(search("ALIŞVERİŞ"))).containsExactly(a);
        assertThat(ids(search("istanbul"))).containsExactly(b);
        assertThat(ids(search("ıstanbul"))).containsExactly(b);
        // LIKE wildcards in the query are literal characters.
        assertThat(ids(search("%50_"))).containsExactly(c);
        assertThat(ids(search("%%"))).isEmpty();
        assertThat(ids(search("__"))).isEmpty();
    }

    // --- authentication and isolation ---------------------------------------------------------

    @Test
    void everyEndpointRequiresAuthentication() throws Exception {
        String id = createExpense(body("1", TODAY, market));
        String json = body("1", TODAY, market).toString();
        List<MockHttpServletRequestBuilder> requests = List.of(
                get(BASE), get(BASE + "/" + id),
                post(BASE).contentType(MediaType.APPLICATION_JSON).content(json),
                put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content(json),
                delete(BASE + "/" + id));
        for (MockHttpServletRequestBuilder request : requests) {
            mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        for (MockHttpServletRequestBuilder request : requests) {
            mvc.perform(request.header("Authorization", "Bearer not-a-token")).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token()))
                .andExpect(status().isNoContent());
        perform(list(token())).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        assertThat(count()).isOne();
    }

    @Test
    void usersOnlySeeTheirOwnExpenses() throws Exception {
        Tokens other = ExpenseTestSupport.register(mvc, null);
        String mine = createExpense(body("10", TODAY, market));
        perform(create(other.accessToken(), body("20", TODAY, categoryId(mvc, other.accessToken(), "GROCERIES"))))
                .andExpect(status().isCreated());

        assertThat(ids(json(perform(list(token())).andReturn()))).containsExactly(mine);
        assertThat(ids(json(perform(list(other.accessToken())).andReturn()))).hasSize(1).doesNotContain(mine);
        // Filtering by someone else's category shows nothing either.
        perform(list(other.accessToken()).param("categoryId", market)).andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void anotherUsersExpenseIsNotFoundAndUnchanged() throws Exception {
        Tokens other = ExpenseTestSupport.register(mvc, null);
        String mine = createExpense(body("10", TODAY, market));
        String attacker = other.accessToken();
        String attackersCategory = categoryId(mvc, attacker, "GROCERIES");

        perform(fetch(attacker, mine)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        perform(replace(attacker, mine, body("99", TODAY, attackersCategory))).andExpect(status().isNotFound());
        perform(remove(attacker, mine)).andExpect(status().isNotFound());

        perform(fetch(token(), mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value("10.00"))
                .andExpect(jsonPath("$.category.id").value(market));
    }

    @Test
    void anotherUsersCategoryCannotBeUsed() throws Exception {
        Tokens other = ExpenseTestSupport.register(mvc, null);
        String othersCategory = categoryId(mvc, other.accessToken(), "GROCERIES");

        perform(create(token(), body("1", TODAY, othersCategory)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        String mine = createExpense(body("1", TODAY, market));
        perform(replace(token(), mine, body("1", TODAY, othersCategory))).andExpect(status().isNotFound());

        perform(fetch(token(), mine)).andExpect(jsonPath("$.category.id").value(market));
        assertThat(jdbc.sql("SELECT count(*) FROM expenses WHERE category_id = :c")
                .param("c", UUID.fromString(othersCategory)).query(Integer.class).single()).isZero();
    }

    @Test
    void ownerIsAlwaysTakenFromTheToken() throws Exception {
        Tokens other = ExpenseTestSupport.register(mvc, null);
        ObjectNode request = body("1", TODAY, market);
        request.put("userId", other.userId());
        perform(create(token(), request)).andExpect(status().isCreated());

        perform(list(other.accessToken())).andExpect(jsonPath("$.totalItems").value(0));
        perform(list(token())).andExpect(jsonPath("$.totalItems").value(1));
    }

    // --- helpers ------------------------------------------------------------------------------

    private String token() {
        return user.accessToken();
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    private String createExpense(JsonNode request) throws Exception {
        return json(perform(create(token(), request)).andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private String createWithDescription(String description) throws Exception {
        ObjectNode request = body("1", TODAY, market);
        request.put("description", description);
        return createExpense(request);
    }

    private JsonNode search(String q) throws Exception {
        return json(perform(list(token()).param("q", q)).andExpect(status().isOk()).andReturn());
    }

    private void archive(String categoryId) throws Exception {
        perform(post("/api/v1/categories/" + categoryId + "/archive").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk());
    }

    private int count() {
        return jdbc.sql("SELECT count(*) FROM expenses WHERE user_id = :u")
                .param("u", UUID.fromString(user.userId())).query(Integer.class).single();
    }

    private void expectValidationError(MockHttpServletRequestBuilder request, String field) throws Exception {
        perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)));
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(e -> ids.add(e.get("id").asString()));
        return ids;
    }
}
