package com.aiexpense.auth;

import static com.aiexpense.support.AuthTestSupport.PASSWORD;
import static com.aiexpense.support.AuthTestSupport.fromIp;
import static com.aiexpense.support.AuthTestSupport.loginJson;
import static com.aiexpense.support.AuthTestSupport.nextIp;
import static com.aiexpense.support.AuthTestSupport.postJson;
import static com.aiexpense.support.AuthTestSupport.registerJson;
import static com.aiexpense.support.AuthTestSupport.uniqueEmail;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiexpense.support.AuthTestSupport;
import com.aiexpense.support.DatabaseIntegrationTest;
import com.aiexpense.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Login and registration rate limits per client IP (docs/architecture.md section 3.6). */
@Import(AuthTestSupport.Config.class)
class RateLimitApiTest extends DatabaseIntegrationTest {

    // application.yml: login 10 attempts / 5 minutes, register 10 / 1 hour.
    static final int LOGIN_LIMIT = 10;
    static final int REGISTER_LIMIT = 10;

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(AuthTestSupport.START);
    }

    @Test
    void loginIsLimitedPerIpAndRecoversAfterTheWindow() throws Exception {
        String email = uniqueEmail();
        AuthTestSupport.register(mvc, email);
        String ip = nextIp();

        for (int i = 0; i < LOGIN_LIMIT; i++) {
            mvc.perform(postJson("/api/v1/auth/login", loginJson(email, "wrong-password-" + i)).with(fromIp(ip)))
                    .andExpect(status().isUnauthorized());
        }
        // Even the right password is refused once the limit is reached.
        mvc.perform(postJson("/api/v1/auth/login", loginJson(email, PASSWORD)).with(fromIp(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", "300"));

        // Another IP is not affected.
        mvc.perform(postJson("/api/v1/auth/login", loginJson(email, PASSWORD)).with(fromIp(nextIp())))
                .andExpect(status().isOk());

        clock.advance(Duration.ofMinutes(2));
        mvc.perform(postJson("/api/v1/auth/login", loginJson(email, PASSWORD)).with(fromIp(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "180"));

        clock.advance(Duration.ofMinutes(3));
        mvc.perform(postJson("/api/v1/auth/login", loginJson(email, PASSWORD)).with(fromIp(ip)))
                .andExpect(status().isOk());
    }

    @Test
    void registrationIsLimitedPerIp() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < REGISTER_LIMIT; i++) {
            mvc.perform(postJson("/api/v1/auth/register", registerJson(uniqueEmail(), PASSWORD, "Ali", null)).with(fromIp(ip)))
                    .andExpect(status().isCreated());
        }
        mvc.perform(postJson("/api/v1/auth/register", registerJson(uniqueEmail(), PASSWORD, "Ali", null)).with(fromIp(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
    }
}
