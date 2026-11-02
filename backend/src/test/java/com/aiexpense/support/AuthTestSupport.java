package com.aiexpense.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Helpers for tests that go through the real authentication API. */
public final class AuthTestSupport {

    /** E-mail domain of every user created by tests; @AfterEach cleanup deletes by it. */
    public static final String TEST_DOMAIN = "@auth-tests.example";
    /** A password that satisfies the rules. Test-only, not used anywhere else. */
    public static final String PASSWORD = "correct-horse-battery";
    public static final Instant START = Instant.parse("2026-11-01T10:00:00Z");

    private static final AtomicInteger IP_COUNTER = new AtomicInteger(1);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AuthTestSupport() {
    }

    /** Test clock (primary Clock bean) and a protected test-only endpoint. */
    @TestConfiguration
    @Import(WhoAmIController.class)
    public static class Config {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START);
        }
    }

    /**
     * Protected endpoint used to test access tokens; exists only in tests. Marked as a test
     * configuration so component scanning skips it; it is only added through {@link Config}.
     */
    @TestConfiguration
    @RestController
    public static class WhoAmIController {
        @GetMapping("/api/v1/test-only/whoami")
        String whoAmI(@AuthenticationPrincipal Jwt jwt) {
            return "{\"userId\":\"" + jwt.getSubject() + "\",\"sessionId\":\"" + jwt.getClaimAsString("sid") + "\"}";
        }
    }

    /** Tokens returned by register/login/refresh. */
    public record Tokens(String accessToken, String refreshToken, String userId) {
    }

    /** A different client IP per call, so the per-IP rate limit never couples unrelated tests. */
    public static RequestPostProcessor uniqueClientIp() {
        return fromIp(nextIp());
    }

    public static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    public static String nextIp() {
        int n = IP_COUNTER.getAndIncrement();
        return "10." + ((n >> 16) & 0xff) + "." + ((n >> 8) & 0xff) + "." + (n & 0xff);
    }

    public static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + TEST_DOMAIN;
    }

    public static String registerJson(String email, String password, String displayName, String timeZone) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"email\":").append(quote(email)).append(',');
        json.append("\"password\":").append(quote(password)).append(',');
        json.append("\"displayName\":").append(quote(displayName));
        if (timeZone != null) {
            json.append(",\"timeZone\":").append(quote(timeZone));
        }
        return json.append('}').toString();
    }

    public static String loginJson(String email, String password) {
        return "{\"email\":" + quote(email) + ",\"password\":" + quote(password) + "}";
    }

    public static String refreshJson(String refreshToken) {
        return "{\"refreshToken\":" + quote(refreshToken) + "}";
    }

    public static MockHttpServletRequestBuilder postJson(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    public static MockHttpServletRequestBuilder whoAmI(String accessToken) {
        return get("/api/v1/test-only/whoami").header("Authorization", "Bearer " + accessToken);
    }

    public static Tokens register(MockMvc mvc, String email) throws Exception {
        MvcResult result = mvc.perform(postJson("/api/v1/auth/register", registerJson(email, PASSWORD, "Test", null))
                .with(uniqueClientIp())).andReturn();
        return tokens(result, 201);
    }

    public static Tokens login(MockMvc mvc, String email) throws Exception {
        MvcResult result = mvc.perform(postJson("/api/v1/auth/login", loginJson(email, PASSWORD))
                .with(uniqueClientIp())).andReturn();
        return tokens(result, 200);
    }

    public static MvcResult refresh(MockMvc mvc, String refreshToken) throws Exception {
        return mvc.perform(postJson("/api/v1/auth/refresh", refreshJson(refreshToken))).andReturn();
    }

    public static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static Tokens tokens(MvcResult result, int expectedStatus) throws Exception {
        if (result.getResponse().getStatus() != expectedStatus) {
            throw new AssertionError("Expected " + expectedStatus + " but got " + result.getResponse().getStatus()
                    + ": " + result.getResponse().getContentAsString());
        }
        JsonNode body = json(result);
        JsonNode user = body.get("user");
        return new Tokens(body.get("accessToken").asString(), body.get("refreshToken").asString(),
                user == null ? null : user.get("id").asString());
    }

    private static String quote(String value) {
        return value == null ? "null" : JSON.writeValueAsString(value);
    }
}
