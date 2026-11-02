package com.aiexpense.auth;

import static com.aiexpense.support.AuthTestSupport.uniqueEmail;
import static com.aiexpense.support.AuthTestSupport.whoAmI;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiexpense.support.AuthTestSupport;
import com.aiexpense.support.AuthTestSupport.Tokens;
import com.aiexpense.support.DatabaseIntegrationTest;
import com.aiexpense.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Unauthorized access to protected endpoints (FR-1.4, docs/architecture.md section 3.6). */
@Import(AuthTestSupport.Config.class)
class AccessTokenApiTest extends DatabaseIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(AuthTestSupport.START);
    }

    @Test
    void validAccessTokenIsAccepted() throws Exception {
        Tokens tokens = AuthTestSupport.register(mvc, uniqueEmail());

        mvc.perform(whoAmI(tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(tokens.userId()));
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/test-only/whoami"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void malformedTokenIsUnauthorized() throws Exception {
        for (String token : new String[] {"abc", "a.b.c", "Basic dXNlcjpwYXNz"}) {
            mvc.perform(whoAmI(token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void tamperedTokenIsUnauthorized() throws Exception {
        Tokens victim = AuthTestSupport.register(mvc, uniqueEmail());
        Tokens attacker = AuthTestSupport.register(mvc, uniqueEmail());
        String[] parts = attacker.accessToken().split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace(attacker.userId(), victim.userId());
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        mvc.perform(whoAmI(forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void accessTokenExpiresAfter15Minutes() throws Exception {
        Tokens tokens = AuthTestSupport.register(mvc, uniqueEmail());

        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        mvc.perform(whoAmI(tokens.accessToken())).andExpect(status().isOk());

        clock.advance(Duration.ofSeconds(1));
        mvc.perform(whoAmI(tokens.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));

        // The refresh token still works and gives a fresh access token.
        String fresh = AuthTestSupport.json(AuthTestSupport.refresh(mvc, tokens.refreshToken()))
                .get("accessToken").asString();
        mvc.perform(whoAmI(fresh)).andExpect(status().isOk());
    }

    @Test
    void refreshTokenIsNotAnAccessToken() throws Exception {
        Tokens tokens = AuthTestSupport.register(mvc, uniqueEmail());

        mvc.perform(whoAmI(tokens.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
