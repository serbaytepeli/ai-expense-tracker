package com.aiexpense.auth.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT security (docs/architecture.md section 3.6). Public: health, register, login,
 * refresh and (dev only) the OpenAPI document. Everything else needs a valid access token whose
 * session is still active.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String ERROR_TOKEN_EXPIRED = "token_expired";
    static final String ERROR_SESSION_REVOKED = "session_revoked";
    static final String ERROR_INVALID_CLAIMS = "invalid_claims";
    static final int BCRYPT_COST = 12;

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ProblemSecurityHandlers problems) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // bearer tokens only, no cookies
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        // Only served in the dev profile (springdoc is disabled otherwise).
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST);
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties jwt) {
        return NimbusJwtEncoder.withSecretKey(secretKey(jwt)).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties jwt, AuthProperties auth, SessionStore sessions, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(jwt)).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtIssuerValidator(auth.issuer()),
                expiry(clock),
                activeSession(sessions)));
        return decoder;
    }

    /** Expired access tokens get their own error so the client sees TOKEN_EXPIRED. Uses the injected Clock. */
    static OAuth2TokenValidator<Jwt> expiry(Clock clock) {
        return token -> token.getExpiresAt() != null && clock.instant().isBefore(token.getExpiresAt())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(ERROR_TOKEN_EXPIRED, "Access token has expired", null));
    }

    /**
     * Checked on EVERY request: the session in {@code sid} must exist, belong to {@code sub} and
     * not be revoked. Logout, token reuse and account deletion therefore take effect immediately.
     */
    static OAuth2TokenValidator<Jwt> activeSession(SessionStore sessions) {
        return token -> {
            UUID userId;
            UUID sessionId;
            try {
                userId = UUID.fromString(token.getSubject());
                sessionId = UUID.fromString(token.getClaimAsString(AccessTokens.SESSION_CLAIM));
            } catch (RuntimeException e) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(ERROR_INVALID_CLAIMS, "Invalid token claims", null));
            }
            return sessions.isActive(sessionId, userId)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(ERROR_SESSION_REVOKED, "Session has been revoked", null));
        };
    }

    private static SecretKey secretKey(JwtProperties jwt) {
        return new SecretKeySpec(jwt.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
