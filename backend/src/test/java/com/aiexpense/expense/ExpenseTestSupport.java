package com.aiexpense.expense;

import static com.aiexpense.support.AuthTestSupport.json;
import static com.aiexpense.support.AuthTestSupport.postJson;
import static com.aiexpense.support.AuthTestSupport.registerJson;
import static com.aiexpense.support.AuthTestSupport.uniqueClientIp;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.aiexpense.support.AuthTestSupport;
import com.aiexpense.support.AuthTestSupport.Tokens;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Request builders for the expense API. */
final class ExpenseTestSupport {

    static final String BASE = "/api/v1/expenses";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ExpenseTestSupport() {
    }

    /** Registers a user in the given IANA time zone (null = default). */
    static Tokens register(MockMvc mvc, String timeZone) throws Exception {
        MvcResult result = mvc.perform(postJson("/api/v1/auth/register",
                registerJson(AuthTestSupport.uniqueEmail(), AuthTestSupport.PASSWORD, "Test", timeZone))
                .with(uniqueClientIp())).andReturn();
        if (result.getResponse().getStatus() != 201) {
            throw new AssertionError("register failed: " + result.getResponse().getContentAsString());
        }
        JsonNode body = json(result);
        return new Tokens(body.get("accessToken").asString(), body.get("refreshToken").asString(),
                body.get("user").get("id").asString());
    }

    /** A valid request body; tests change single fields with {@link ObjectNode#put}. */
    static ObjectNode body(String amount, String date, String categoryId) {
        ObjectNode node = JSON.createObjectNode();
        node.put("amount", amount);
        node.put("date", date);
        node.put("categoryId", categoryId);
        return node;
    }

    static MockHttpServletRequestBuilder create(String token, JsonNode body) {
        return post(BASE).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString());
    }

    static MockHttpServletRequestBuilder createRaw(String token, String json) {
        return post(BASE).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    static MockHttpServletRequestBuilder replace(String token, String id, JsonNode body) {
        return put(BASE + "/" + id).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString());
    }

    static MockHttpServletRequestBuilder fetch(String token, String id) {
        return get(BASE + "/" + id).header("Authorization", "Bearer " + token);
    }

    static MockHttpServletRequestBuilder remove(String token, String id) {
        return delete(BASE + "/" + id).header("Authorization", "Bearer " + token);
    }

    static MockHttpServletRequestBuilder list(String token) {
        return get(BASE).header("Authorization", "Bearer " + token);
    }

    /** ID of the user's category with the given system key (built-in categories). */
    static String categoryId(MockMvc mvc, String token, String systemKey) throws Exception {
        JsonNode categories = json(mvc.perform(get("/api/v1/categories").param("includeArchived", "true")
                .header("Authorization", "Bearer " + token)).andReturn());
        for (JsonNode c : categories) {
            if (systemKey.equals(c.path("systemKey").asString(null))) {
                return c.get("id").asString();
            }
        }
        throw new AssertionError("no category " + systemKey);
    }
}
