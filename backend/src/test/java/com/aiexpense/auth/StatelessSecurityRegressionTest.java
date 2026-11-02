package com.aiexpense.auth;

import static com.aiexpense.support.AuthTestSupport.PASSWORD;
import static com.aiexpense.support.AuthTestSupport.loginJson;
import static com.aiexpense.support.AuthTestSupport.postJson;
import static com.aiexpense.support.AuthTestSupport.refreshJson;
import static com.aiexpense.support.AuthTestSupport.registerJson;
import static com.aiexpense.support.AuthTestSupport.uniqueClientIp;
import static com.aiexpense.support.AuthTestSupport.uniqueEmail;
import static com.aiexpense.support.AuthTestSupport.whoAmI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiexpense.support.AuthTestSupport;
import com.aiexpense.support.AuthTestSupport.Tokens;
import com.aiexpense.support.DatabaseIntegrationTest;
import com.aiexpense.support.MutableClock;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Regression tests for the reason CSRF protection is disabled (SecurityConfiguration).
 *
 * <p>CSRF needs credentials that a browser attaches automatically: cookies (including a session
 * cookie) or HTTP Basic credentials. This API must only ever accept an explicit
 * {@code Authorization: Bearer} header. If any of these tests fails, the CodeQL
 * "Disabled Spring CSRF protection" alert is no longer a false positive and CSRF must be
 * reconsidered.
 */
@Import(AuthTestSupport.Config.class)
class StatelessSecurityRegressionTest extends DatabaseIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(AuthTestSupport.START);
    }

    // --- 1. No HTTP session -------------------------------------------------------------------

    @Test
    void noAuthenticationStepCreatesAnHttpSession() throws Exception {
        String email = uniqueEmail();
        MvcResult register = mvc.perform(postJson("/api/v1/auth/register", registerJson(email, PASSWORD, "Ali", null))
                .with(uniqueClientIp())).andExpect(status().isCreated()).andReturn();
        MvcResult login = mvc.perform(postJson("/api/v1/auth/login", loginJson(email, PASSWORD))
                .with(uniqueClientIp())).andExpect(status().isOk()).andReturn();
        String accessToken = AuthTestSupport.json(login).get("accessToken").asString();
        String refreshToken = AuthTestSupport.json(login).get("refreshToken").asString();
        MvcResult refresh = mvc.perform(postJson("/api/v1/auth/refresh", refreshJson(refreshToken)))
                .andExpect(status().isOk()).andReturn();
        MvcResult protectedCall = mvc.perform(whoAmI(accessToken)).andExpect(status().isOk()).andReturn();
        MvcResult failedLogin = mvc.perform(postJson("/api/v1/auth/login", loginJson(email, "wrong-password-1"))
                .with(uniqueClientIp())).andExpect(status().isUnauthorized()).andReturn();
        MvcResult unauthenticated = mvc.perform(get("/api/v1/test-only/whoami"))
                .andExpect(status().isUnauthorized()).andReturn();
        MvcResult logout = mvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent()).andReturn();

        for (MvcResult result : List.of(register, login, refresh, protectedCall, failedLogin, unauthenticated, logout)) {
            assertThat(result.getRequest().getSession(false))
                    .as("HTTP session for %s", result.getRequest().getRequestURI())
                    .isNull();
        }
    }

    // --- 2. No cookies ------------------------------------------------------------------------

    @Test
    void noAuthenticationResponseSetsACookie() throws Exception {
        String email = uniqueEmail();
        MvcResult register = mvc.perform(postJson("/api/v1/auth/register", registerJson(email, PASSWORD, "Ali", null))
                .with(uniqueClientIp())).andReturn();
        MvcResult login = mvc.perform(postJson("/api/v1/auth/login", loginJson(email, PASSWORD))
                .with(uniqueClientIp())).andReturn();
        String accessToken = AuthTestSupport.json(login).get("accessToken").asString();
        String refreshToken = AuthTestSupport.json(login).get("refreshToken").asString();
        MvcResult refresh = mvc.perform(postJson("/api/v1/auth/refresh", refreshJson(refreshToken))).andReturn();
        MvcResult failedLogin = mvc.perform(postJson("/api/v1/auth/login", loginJson(email, "wrong-password-2"))
                .with(uniqueClientIp())).andReturn();
        MvcResult unauthenticated = mvc.perform(get("/api/v1/test-only/whoami")).andReturn();
        MvcResult logout = mvc.perform(post("/api/v1/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)).andReturn();

        for (MvcResult result : List.of(register, login, refresh, failedLogin, unauthenticated, logout)) {
            assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                    .as("Set-Cookie on %s", result.getRequest().getRequestURI())
                    .isEmpty();
            assertThat(result.getResponse().getCookies())
                    .as("cookies on %s", result.getRequest().getRequestURI())
                    .isEmpty();
        }
    }

    // --- 3. Protected endpoints require a valid Bearer token ---------------------------------

    @Test
    void protectedEndpointRequiresAValidBearerToken() throws Exception {
        Tokens tokens = AuthTestSupport.register(mvc, uniqueEmail());

        mvc.perform(whoAmI(tokens.accessToken())).andExpect(status().isOk());

        assertUnauthorized(get("/api/v1/test-only/whoami"));
        assertUnauthorized(get("/api/v1/test-only/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer "));
        assertUnauthorized(get("/api/v1/test-only/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"));
        assertUnauthorized(get("/api/v1/test-only/whoami").header(HttpHeaders.AUTHORIZATION, "Token " + tokens.accessToken()));
        assertUnauthorized(get("/api/v1/test-only/whoami").header(HttpHeaders.AUTHORIZATION, tokens.accessToken()));
        // The token is only accepted from the Authorization header, not from a query parameter.
        assertUnauthorized(get("/api/v1/test-only/whoami").param("access_token", tokens.accessToken()));
    }

    // --- 4. No authentication through cookies or HTTP Basic ----------------------------------

    @Test
    void validAccessTokenInACookieDoesNotAuthenticate() throws Exception {
        Tokens tokens = AuthTestSupport.register(mvc, uniqueEmail());

        for (String name : new String[] {"access_token", "Authorization", "token", "JSESSIONID"}) {
            assertUnauthorized(get("/api/v1/test-only/whoami").cookie(new Cookie(name, tokens.accessToken())));
        }
        // A state-changing request with only a cookie is rejected too (what a CSRF attack would send).
        assertUnauthorized(post("/api/v1/auth/logout").cookie(new Cookie("access_token", tokens.accessToken())));
        mvc.perform(whoAmI(tokens.accessToken())).andExpect(status().isOk()); // the session was not logged out
    }

    @Test
    void preAuthenticatedHttpSessionDoesNotAuthenticate() throws Exception {
        // Even a session that already holds an authenticated security context is ignored.
        SecurityContext context = new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                "attacker", null, AuthorityUtils.createAuthorityList("ROLE_USER")));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        assertUnauthorized(get("/api/v1/test-only/whoami").session(session));
        assertUnauthorized(post("/api/v1/auth/logout").session(session));
    }

    @Test
    void httpBasicCredentialsDoNotAuthenticate() throws Exception {
        String email = uniqueEmail();
        AuthTestSupport.register(mvc, email);
        String basic = "Basic " + Base64.getEncoder().encodeToString((email + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));

        mvc.perform(get("/api/v1/test-only/whoami").header(HttpHeaders.AUTHORIZATION, basic))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                // Only Bearer is offered; the browser is never asked for Basic credentials.
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        assertUnauthorized(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, basic));
    }

    private void assertUnauthorized(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andReturn();
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }
}
