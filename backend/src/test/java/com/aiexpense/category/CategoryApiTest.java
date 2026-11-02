package com.aiexpense.category;

import static com.aiexpense.category.CategoryTestSupport.BASE;
import static com.aiexpense.category.CategoryTestSupport.archive;
import static com.aiexpense.category.CategoryTestSupport.create;
import static com.aiexpense.category.CategoryTestSupport.list;
import static com.aiexpense.category.CategoryTestSupport.listAll;
import static com.aiexpense.category.CategoryTestSupport.unarchive;
import static com.aiexpense.category.CategoryTestSupport.update;
import static com.aiexpense.support.AuthTestSupport.json;
import static com.aiexpense.support.AuthTestSupport.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** Category API (docs/api-contract.md section 4, FR-3). */
@Import(AuthTestSupport.Config.class)
class CategoryApiTest extends DatabaseIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    Tokens user;

    @BeforeEach
    void registerUser() throws Exception {
        clock.set(AuthTestSupport.START);
        user = AuthTestSupport.register(mvc, uniqueEmail());
    }

    // --- create -------------------------------------------------------------------------------

    @Test
    void createAddsACustomCategoryAtTheEndOfTheList() throws Exception {
        perform(create(token(), "  Evcil Hayvan  ", "paw-outline", "#00aa11"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Evcil Hayvan"))
                .andExpect(jsonPath("$.icon").value("paw-outline"))
                .andExpect(jsonPath("$.color").value("#00AA11"))
                .andExpect(jsonPath("$.sortOrder").value(12))
                .andExpect(jsonPath("$.archived").value(false));

        JsonNode categories = body(list(token()));
        assertThat(categories.size()).isEqualTo(12);
        assertThat(categories.get(11).has("systemKey")).as("systemKey is present as null").isTrue();
        assertThat(categories.get(11).get("systemKey").isNull()).isTrue();
        assertThat(categories.get(11).get("name").asString()).isEqualTo("Evcil Hayvan");
    }

    @Test
    void nameLengthIsCountedAfterTrimmingInCharacters() throws Exception {
        String thirty = "ğ".repeat(30);
        perform(create(token(), "  " + thirty + "  ", "a", "#000000"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(thirty));
        expectValidationError(create(token(), "ğ".repeat(31), "a", "#000000"), "name");
        expectValidationError(create(token(), "   ", "a", "#000000"), "name");
        expectValidationError(create(token(), "", "a", "#000000"), "name");
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        expectValidationError(create(token(), "{\"icon\":\"a\",\"color\":\"#000000\"}"), "name");
        expectValidationError(create(token(), "{\"name\":\"X\",\"color\":\"#000000\"}"), "icon");
        expectValidationError(create(token(), "{\"name\":\"X\",\"icon\":\"a\"}"), "color");
        for (String icon : new String[] {"", "Cart", "cart_outline", "cart outline", "ç", "a".repeat(41)}) {
            expectValidationError(create(token(), "X", icon, "#000000"), "icon");
        }
        for (String color : new String[] {"", "red", "#12345", "#1234567", "123456", "#GGGGGG"}) {
            expectValidationError(create(token(), "X", "a", color), "color");
        }
        perform(create(token(), "not json")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(body(list(token())).size()).as("nothing was created").isEqualTo(11);
    }

    @Test
    void iconAtMaximumLengthIsAccepted() throws Exception {
        perform(create(token(), "X", "a-1".repeat(13) + "z", "#000000"))
                .andExpect(status().isCreated());
    }

    // --- name uniqueness (database index on lower(name), active categories only) -------------------

    @Test
    void duplicateNamesAreRejectedIgnoringCaseAndSurroundingSpaces() throws Exception {
        perform(create(token(), "Kitap", "book", "#000000")).andExpect(status().isCreated());

        for (String name : new String[] {"Kitap", "KITAP", "kitap", "  kiTAP "}) {
            perform(create(token(), name, "book", "#000000"))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN"))
                    .andExpect(jsonPath("$.requestId").isNotEmpty());
        }
        // Built-in names are taken too.
        perform(create(token(), "market", "a", "#000000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN"));
    }

    @Test
    void renameToAnotherActiveCategorysNameIsRejected() throws Exception {
        String market = idOf("GROCERIES");
        perform(update(token(), market, "{\"name\":\" ulaşım \"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN"));
        // Changing only the case of its own name is fine.
        perform(update(token(), market, "{\"name\":\"MARKET\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("MARKET"));
    }

    @Test
    void theSameNameCanBeUsedByDifferentUsers() throws Exception {
        Tokens other = AuthTestSupport.register(mvc, uniqueEmail());
        perform(create(token(), "Kitap", "book", "#000000")).andExpect(status().isCreated());
        perform(create(other.accessToken(), "Kitap", "book", "#000000")).andExpect(status().isCreated());
    }

    // --- list ----------------------------------------------------------------------------------

    @Test
    void listIsOrderedBySortOrderThenName() throws Exception {
        String bills = idOf("BILLS");
        perform(update(token(), bills, "{\"sortOrder\":1}")).andExpect(status().isOk());
        perform(update(token(), idOf("OTHER"), "{\"sortOrder\":0}")).andExpect(status().isOk());

        List<String> names = names(body(list(token())));
        assertThat(names.subList(0, 4)).containsExactly("Diğer", "Faturalar", "Market", "Yeme-İçme");
    }

    // --- update ----------------------------------------------------------------------------------

    @Test
    void patchChangesOnlyTheGivenFields() throws Exception {
        String health = idOf("HEALTH");
        perform(update(token(), health, "{\"name\":\" Sağlık & Spor \",\"color\":\"#abcdef\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(health))
                .andExpect(jsonPath("$.name").value("Sağlık & Spor"))
                .andExpect(jsonPath("$.color").value("#ABCDEF"))
                .andExpect(jsonPath("$.icon").value("medkit-outline"))
                .andExpect(jsonPath("$.sortOrder").value(6))
                .andExpect(jsonPath("$.systemKey").value("HEALTH"));

        perform(update(token(), health, "{\"icon\":\"fitness-outline\",\"sortOrder\":40}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sağlık & Spor"))
                .andExpect(jsonPath("$.icon").value("fitness-outline"))
                .andExpect(jsonPath("$.sortOrder").value(40));
    }

    @Test
    void patchRecordsTheUpdateTime() throws Exception {
        String health = idOf("HEALTH");
        clock.advance(Duration.ofMinutes(5));
        perform(update(token(), health, "{\"icon\":\"fitness-outline\"}")).andExpect(status().isOk());

        var times = jdbc.sql("SELECT created_at <> updated_at FROM categories WHERE id = :id")
                .param("id", UUID.fromString(health)).query(Boolean.class).single();
        assertThat(times).isTrue();
    }

    @Test
    void invalidPatchIsRejected() throws Exception {
        String health = idOf("HEALTH");
        expectValidationError(update(token(), health, "{\"name\":\"  \"}"), "name");
        expectValidationError(update(token(), health, "{\"name\":\"" + "x".repeat(31) + "\"}"), "name");
        expectValidationError(update(token(), health, "{\"icon\":\"Bad Icon\"}"), "icon");
        expectValidationError(update(token(), health, "{\"color\":\"#12\"}"), "color");
        expectValidationError(update(token(), health, "{\"sortOrder\":-1}"), "sortOrder");
        expectValidationError(update(token(), health, "{\"sortOrder\":32768}"), "sortOrder");
        perform(update(token(), health, "{\"sortOrder\":99999999999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        perform(update(token(), health, "{\"sortOrder\":32767}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sortOrder").value(32767));
        perform(update(token(), health, "{\"sortOrder\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sortOrder").value(0));
    }

    @Test
    void archivedCategoriesCanBeEdited() throws Exception {
        String clothing = idOf("CLOTHING");
        perform(archive(token(), clothing)).andExpect(status().isOk());

        perform(update(token(), clothing, "{\"name\":\"Kıyafet\",\"icon\":\"shirt\",\"color\":\"#111111\","
                + "\"sortOrder\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Kıyafet"))
                .andExpect(jsonPath("$.archived").value(true));
    }

    @Test
    void anArchivedCategoryMayTakeTheNameOfAnActiveOne() throws Exception {
        // Uniqueness only covers active categories, so this is allowed; unarchiving it is not.
        String clothing = idOf("CLOTHING");
        perform(archive(token(), clothing)).andExpect(status().isOk());
        perform(update(token(), clothing, "{\"name\":\"market\"}")).andExpect(status().isOk());

        perform(unarchive(token(), clothing))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN"));
    }

    // --- archive / unarchive ------------------------------------------------------------------------

    @Test
    void archivedCategoriesAreHiddenUnlessRequested() throws Exception {
        String transport = idOf("TRANSPORT");
        perform(archive(token(), transport))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transport))
                .andExpect(jsonPath("$.archived").value(true));

        perform(list(token()))
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[*].id", not(hasItem(transport))));
        perform(list(token()).param("includeArchived", "false"))
                .andExpect(jsonPath("$.length()").value(10));
        perform(listAll(token()))
                .andExpect(jsonPath("$.length()").value(11))
                .andExpect(jsonPath("$[?(@.id == '" + transport + "')].archived").value(true));
    }

    @Test
    void archivingTwiceKeepsTheFirstArchiveTime() throws Exception {
        String transport = idOf("TRANSPORT");
        perform(archive(token(), transport)).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(5));
        perform(archive(token(), transport)).andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));

        var archivedAt = jdbc.sql("SELECT archived_at FROM categories WHERE id = :id")
                .param("id", UUID.fromString(transport)).query(java.time.OffsetDateTime.class).single();
        assertThat(archivedAt.toInstant()).isEqualTo(AuthTestSupport.START);
    }

    @Test
    void archivedNameCanBeReusedAndThenBlocksUnarchive() throws Exception {
        String transport = idOf("TRANSPORT");
        perform(archive(token(), transport)).andExpect(status().isOk());

        perform(create(token(), "  ulaşım ", "car", "#000000")).andExpect(status().isCreated());

        perform(unarchive(token(), transport))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        // The failed unarchive changed nothing.
        perform(listAll(token()))
                .andExpect(jsonPath("$[?(@.id == '" + transport + "')].archived").value(true));
    }

    @Test
    void unarchiveMakesTheCategoryActiveAgain() throws Exception {
        String transport = idOf("TRANSPORT");
        perform(archive(token(), transport)).andExpect(status().isOk());

        perform(unarchive(token(), transport))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.sortOrder").value(3));
        perform(list(token())).andExpect(jsonPath("$.length()").value(11));
        // Unarchiving an active category is a no-op.
        perform(unarchive(token(), transport)).andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false));
    }

    @Test
    void otherCannotBeArchivedButCanBeRenamed() throws Exception {
        String other = idOf("OTHER");
        perform(archive(token(), other))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CATEGORY_PROTECTED"));
        perform(update(token(), other, "{\"name\":\"Çeşitli\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Çeşitli"))
                .andExpect(jsonPath("$.systemKey").value("OTHER"));
        perform(list(token())).andExpect(jsonPath("$.length()").value(11));
    }

    @Test
    void otherBuiltInCategoriesCanBeArchived() throws Exception {
        for (String key : new String[] {"GROCERIES", "FOOD_DRINK", "TRANSPORT", "BILLS", "HOUSING", "HEALTH",
                "ENTERTAINMENT", "CLOTHING", "EDUCATION", "SUBSCRIPTIONS"}) {
            perform(archive(token(), idOf(key))).andExpect(status().isOk());
        }
        perform(list(token()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].systemKey").value("OTHER"));
    }

    @Test
    void thereIsNoDeleteEndpoint() throws Exception {
        perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(BASE + "/" + idOf("TRANSPORT")).header("Authorization", "Bearer " + token()))
                .andExpect(status().isMethodNotAllowed());
        perform(list(token())).andExpect(jsonPath("$.length()").value(11));
    }

    // --- sortOrder limit ----------------------------------------------------------------------------

    @Test
    void createFailsClearlyWhenNoSortOrderIsLeft() throws Exception {
        perform(update(token(), idOf("HEALTH"), "{\"sortOrder\":32766}")).andExpect(status().isOk());
        perform(create(token(), "Son", "a", "#000000"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sortOrder").value(32767));

        perform(create(token(), "Bir Fazla", "a", "#000000"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CATEGORY_SORT_ORDER_LIMIT"))
                .andExpect(jsonPath("$.type").value("https://aiexpense.app/problems/category-sort-order-limit"));
        assertThat(names(body(listAll(token())))).doesNotContain("Bir Fazla");
    }

    @Test
    void archivedCategoriesCountForTheNextSortOrder() throws Exception {
        String archived = idOf("HEALTH");
        perform(update(token(), archived, "{\"sortOrder\":32767}")).andExpect(status().isOk());
        perform(archive(token(), archived)).andExpect(status().isOk());

        perform(create(token(), "Yeni", "a", "#000000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_SORT_ORDER_LIMIT"));
        // Lowering the highest value frees the slot again.
        perform(update(token(), archived, "{\"sortOrder\":20}")).andExpect(status().isOk());
        perform(create(token(), "Yeni", "a", "#000000"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sortOrder").value(21));
    }

    // --- authentication and isolation ---------------------------------------------------------------

    @Test
    void everyEndpointRequiresAuthentication() throws Exception {
        String id = idOf("TRANSPORT");
        String json = "{\"name\":\"X\",\"icon\":\"a\",\"color\":\"#000000\"}";
        List<MockHttpServletRequestBuilder> requests = List.of(
                get(BASE),
                post(BASE).contentType(MediaType.APPLICATION_JSON).content(json),
                patch(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"),
                post(BASE + "/" + id + "/archive"),
                post(BASE + "/" + id + "/unarchive"));
        for (MockHttpServletRequestBuilder request : requests) {
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        for (MockHttpServletRequestBuilder request : requests) {
            mvc.perform(request.header("Authorization", "Bearer not-a-token"))
                    .andExpect(status().isUnauthorized());
        }
        perform(list(token())).andExpect(jsonPath("$[?(@.systemKey == 'TRANSPORT')].name").value("Ulaşım"));
    }

    @Test
    void loggedOutSessionCannotUseTheApi() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token()))
                .andExpect(status().isNoContent());
        perform(list(token())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }

    @Test
    void usersOnlySeeTheirOwnCategories() throws Exception {
        Tokens other = AuthTestSupport.register(mvc, uniqueEmail());
        perform(create(token(), "Gizli", "a", "#000000")).andExpect(status().isCreated());

        List<String> mine = ids(body(listAll(token())));
        List<String> theirs = ids(body(listAll(other.accessToken())));
        assertThat(mine).hasSize(12).doesNotContainAnyElementsOf(theirs);
        assertThat(theirs).hasSize(11);
        assertThat(names(body(listAll(other.accessToken())))).doesNotContain("Gizli");
    }

    @Test
    void anotherUsersCategoryIsNotFoundAndUnchanged() throws Exception {
        Tokens other = AuthTestSupport.register(mvc, uniqueEmail());
        String mine = idOf("TRANSPORT");
        String attacker = other.accessToken();

        for (MockHttpServletRequestBuilder request : List.of(
                update(attacker, mine, "{\"name\":\"Hacked\",\"sortOrder\":1}"),
                archive(attacker, mine),
                unarchive(attacker, mine))) {
            perform(request)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        perform(archive(token(), mine)).andExpect(status().isOk());
        perform(unarchive(attacker, mine)).andExpect(status().isNotFound());
        perform(listAll(token()))
                .andExpect(jsonPath("$[?(@.id == '" + mine + "')].name").value("Ulaşım"))
                .andExpect(jsonPath("$[?(@.id == '" + mine + "')].archived").value(true));
    }

    @Test
    void ownerIsAlwaysTakenFromTheToken() throws Exception {
        Tokens other = AuthTestSupport.register(mvc, uniqueEmail());
        // A userId field in the body is not part of the contract and must not choose the owner.
        perform(create(token(), "{\"name\":\"Sahte\",\"icon\":\"a\",\"color\":\"#000000\",\"userId\":\""
                + other.userId() + "\"}"))
                .andExpect(status().isCreated());
        assertThat(names(body(list(other.accessToken())))).doesNotContain("Sahte");
        assertThat(names(body(list(token())))).contains("Sahte");
    }

    @Test
    void unknownOrMalformedIdsAreNotFound() throws Exception {
        for (String id : new String[] {UUID.randomUUID().toString(), "not-a-uuid"}) {
            perform(update(token(), id, "{\"name\":\"X\"}")).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            perform(archive(token(), id)).andExpect(status().isNotFound());
            perform(unarchive(token(), id)).andExpect(status().isNotFound());
        }
    }

    // --- helpers ------------------------------------------------------------------------------------

    private String token() {
        return user.accessToken();
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    private JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
        return json(mvc.perform(request).andExpect(status().isOk()).andReturn());
    }

    private String idOf(String systemKey) throws Exception {
        for (JsonNode category : body(listAll(token()))) {
            if (systemKey.equals(category.path("systemKey").asString(null))) {
                return category.get("id").asString();
            }
        }
        throw new AssertionError("No category with systemKey " + systemKey);
    }

    private void expectValidationError(MockHttpServletRequestBuilder request, String field) throws Exception {
        perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)));
    }

    private static List<String> names(JsonNode categories) {
        List<String> names = new ArrayList<>();
        categories.forEach(c -> names.add(c.get("name").asString()));
        return names;
    }

    private static List<String> ids(JsonNode categories) {
        List<String> ids = new ArrayList<>();
        categories.forEach(c -> ids.add(c.get("id").asString()));
        return ids;
    }
}
