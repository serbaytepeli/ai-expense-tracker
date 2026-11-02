package com.aiexpense.auth;

import static com.aiexpense.support.AuthTestSupport.refresh;
import static com.aiexpense.support.AuthTestSupport.uniqueEmail;
import static com.aiexpense.support.AuthTestSupport.whoAmI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiexpense.support.AuthTestSupport;
import com.aiexpense.support.AuthTestSupport.Tokens;
import com.aiexpense.support.DatabaseIntegrationTest;
import com.aiexpense.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * One test per row of the session table in docs/architecture.md section 3.6, with time driven by
 * a test clock. Everything goes through the HTTP API and a real PostgreSQL database.
 */
@Import(AuthTestSupport.Config.class)
class TokenLifecycleApiTest extends DatabaseIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(AuthTestSupport.START);
    }

    @Test
    void normalRotationIssuesANewPairAndInvalidatesTheOldRefreshToken() throws Exception {
        Tokens first = AuthTestSupport.register(mvc, uniqueEmail());

        MvcResult result = refresh(mvc, first.refreshToken());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = AuthTestSupport.json(result);
        String newRefresh = body.get("refreshToken").asString();
        String newAccess = body.get("accessToken").asString();
        assertThat(newRefresh).isNotEqualTo(first.refreshToken());
        assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(body.has("user")).isFalse();

        mvc.perform(whoAmI(newAccess)).andExpect(status().isOk());
        // Same session: the new access token carries the same sid.
        assertThat(sessionOf(newAccess)).isEqualTo(sessionOf(first.accessToken()));
        assertThat(refresh(mvc, newRefresh).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void expiredRefreshTokenIsRejectedWithoutRevokingTheSession() throws Exception {
        Tokens tokens = AuthTestSupport.register(mvc, uniqueEmail());

        clock.advance(Duration.ofDays(30));

        assertProblem(refresh(mvc, tokens.refreshToken()), 401, "TOKEN_EXPIRED");
        assertThat(sessionRevokedReason(tokens.accessToken())).isNull();
    }

    @Test
    void refreshTokenIsStillValidJustBeforeItExpires() throws Exception {
        Tokens tokens = AuthTestSupport.register(mvc, uniqueEmail());

        clock.advance(Duration.ofDays(30).minusSeconds(1));

        assertThat(refresh(mvc, tokens.refreshToken()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void unknownRefreshTokenIsUnauthorized() throws Exception {
        assertProblem(refresh(mvc, "not-a-real-refresh-token"), 401, "UNAUTHORIZED");
    }

    @Test
    void refreshAfterLogoutIsSessionRevoked() throws Exception {
        Tokens tokens = AuthTestSupport.register(mvc, uniqueEmail());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNoContent());

        assertProblem(refresh(mvc, tokens.refreshToken()), 401, "SESSION_REVOKED");
    }

    @Test
    void reuseWithin30SecondsIsAlreadyRotatedAndTheSessionStaysOpen() throws Exception {
        Tokens tokens = AuthTestSupport.register(mvc, uniqueEmail());
        String newRefresh = AuthTestSupport.json(refresh(mvc, tokens.refreshToken())).get("refreshToken").asString();

        clock.advance(Duration.ofSeconds(30));

        assertProblem(refresh(mvc, tokens.refreshToken()), 401, "TOKEN_ALREADY_ROTATED");
        assertThat(sessionRevokedReason(tokens.accessToken())).isNull();
        mvc.perform(whoAmI(tokens.accessToken())).andExpect(status().isOk());
        assertThat(refresh(mvc, newRefresh).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void reuseAfter30SecondsRevokesTheWholeSession() throws Exception {
        Tokens tokens = AuthTestSupport.register(mvc, uniqueEmail());
        String newRefresh = AuthTestSupport.json(refresh(mvc, tokens.refreshToken())).get("refreshToken").asString();
        String newAccess = AuthTestSupport.json(refresh(mvc, newRefresh)).get("accessToken").asString();
        // newRefresh was just rotated too; take the last token of the family for the final check.

        clock.advance(Duration.ofSeconds(31));

        assertProblem(refresh(mvc, tokens.refreshToken()), 401, "TOKEN_REUSED");
        assertThat(sessionRevokedReason(tokens.accessToken())).isEqualTo("TOKEN_REUSE");
        // Every token of the session stops working, including the newest access token.
        mvc.perform(whoAmI(newAccess)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        assertProblem(refresh(mvc, newRefresh), 401, "SESSION_REVOKED");
    }

    @Test
    void reuseDetectionOnlyRevokesTheAffectedSession() throws Exception {
        String email = uniqueEmail();
        Tokens phone = AuthTestSupport.register(mvc, email);
        Tokens tablet = AuthTestSupport.login(mvc, email);
        refresh(mvc, phone.refreshToken());

        clock.advance(Duration.ofSeconds(31));
        assertProblem(refresh(mvc, phone.refreshToken()), 401, "TOKEN_REUSED");

        mvc.perform(whoAmI(tablet.accessToken())).andExpect(status().isOk());
        assertThat(refresh(mvc, tablet.refreshToken()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void concurrentRefreshWithTheSameTokenSucceedsOnceAndKeepsTheSession() throws Exception {
        Tokens tokens = AuthTestSupport.register(mvc, uniqueEmail());
        CountDownLatch start = new CountDownLatch(1);
        Callable<MvcResult> attempt = () -> {
            start.await();
            return refresh(mvc, tokens.refreshToken());
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<MvcResult> results = new ArrayList<>();
        try {
            Future<MvcResult> a = pool.submit(attempt);
            Future<MvcResult> b = pool.submit(attempt);
            start.countDown();
            results.add(a.get());
            results.add(b.get());
        } finally {
            pool.shutdownNow();
        }

        List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).toList();
        assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        MvcResult loser = results.stream().filter(r -> r.getResponse().getStatus() == 401).findFirst().orElseThrow();
        assertProblem(loser, 401, "TOKEN_ALREADY_ROTATED");
        MvcResult winner = results.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst().orElseThrow();

        assertThat(sessionRevokedReason(tokens.accessToken())).isNull();
        String newRefresh = AuthTestSupport.json(winner).get("refreshToken").asString();
        assertThat(refresh(mvc, newRefresh).getResponse().getStatus()).isEqualTo(200);
        // Exactly one new token was issued by the two concurrent requests (plus the one just above).
        assertThat(jdbc.sql("""
                SELECT count(*) FROM refresh_tokens WHERE session_id = :sid
                """).param("sid", java.util.UUID.fromString(sessionOf(tokens.accessToken()))).query(Integer.class).single())
                .isEqualTo(3);
    }

    @Test
    void logoutRejectsTheAccessTokenImmediately() throws Exception {
        Tokens tokens = AuthTestSupport.register(mvc, uniqueEmail());
        mvc.perform(whoAmI(tokens.accessToken())).andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isNoContent());

        // No waiting for the 15-minute expiry: the session check runs on every request.
        mvc.perform(whoAmI(tokens.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        assertThat(sessionRevokedReason(tokens.accessToken())).isEqualTo("LOGOUT");
    }

    @Test
    void logoutOnlyEndsTheCurrentSession() throws Exception {
        String email = uniqueEmail();
        Tokens phone = AuthTestSupport.register(mvc, email);
        Tokens tablet = AuthTestSupport.login(mvc, email);

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + phone.accessToken()))
                .andExpect(status().isNoContent());

        mvc.perform(whoAmI(tablet.accessToken())).andExpect(status().isOk());
    }

    @Test
    void logoutRequiresAValidAccessToken() throws Exception {
        mvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void deletedUserTokensStopWorking() throws Exception {
        String email = uniqueEmail();
        Tokens tokens = AuthTestSupport.register(mvc, email);

        jdbc.sql("DELETE FROM users WHERE email = :e").param("e", email).update();

        mvc.perform(whoAmI(tokens.accessToken())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        assertProblem(refresh(mvc, tokens.refreshToken()), 401, "UNAUTHORIZED");
    }

    private String sessionOf(String accessToken) throws Exception {
        MvcResult result = mvc.perform(whoAmI(accessToken)).andReturn();
        return AuthTestSupport.json(result).get("sessionId").asString();
    }

    /** Reads the session id from the token payload without the API (works for revoked sessions). */
    private static String sidClaim(String accessToken) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        return payload.replaceAll(".*\"sid\":\"([^\"]+)\".*", "$1");
    }

    private String sessionRevokedReason(String accessToken) {
        return jdbc.sql("SELECT revoke_reason FROM auth_sessions WHERE id = :id")
                .param("id", java.util.UUID.fromString(sidClaim(accessToken)))
                .query(String.class).optional().orElse(null);
    }

    private static void assertProblem(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(AuthTestSupport.json(result).get("code").asString()).isEqualTo(code);
    }
}
