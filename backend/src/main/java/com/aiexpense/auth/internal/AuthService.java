package com.aiexpense.auth.internal;

import com.aiexpense.common.ApiException;
import com.aiexpense.user.EmailAddresses;
import com.aiexpense.user.UserApi;
import com.aiexpense.user.UserApi.UserView;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login, refresh token rotation and logout (docs/architecture.md section 3.6,
 * docs/api-contract.md section 2).
 */
@Service
class AuthService {

    /** Login/register response: new session with its first token pair. */
    record SessionTokens(AccessTokens.Issued access, String refreshToken, UserView user) {
        @Override
        public String toString() {
            return "SessionTokens[userId=" + (user == null ? null : user.id()) + ", tokens=***]";
        }
    }

    /** Refresh response: a new token pair in the same session. */
    record RotatedTokens(AccessTokens.Issued access, String refreshToken) {
        @Override
        public String toString() {
            return "RotatedTokens[tokens=***]";
        }
    }

    static final int PASSWORD_MIN_CHARS = 8;
    static final int PASSWORD_MAX_CHARS = 72;
    /** BCrypt only uses the first 72 bytes; longer passwords are rejected instead of truncated. */
    static final int PASSWORD_MAX_BYTES = 72;
    static final int DISPLAY_NAME_MAX = 60;

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserApi users;
    private final SessionStore sessions;
    private final AccessTokens accessTokens;
    private final PasswordEncoder passwordEncoder;
    private final AttemptLimiter limiter;
    private final AuthProperties properties;
    private final Clock clock;
    /** Compared against when the e-mail is unknown, so both cases take the same BCrypt time. */
    private final String dummyHash;

    AuthService(UserApi users, SessionStore sessions, AccessTokens accessTokens, PasswordEncoder passwordEncoder,
            AttemptLimiter limiter, AuthProperties properties, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.accessTokens = accessTokens;
        this.passwordEncoder = passwordEncoder;
        this.limiter = limiter;
        this.properties = properties;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(RefreshTokens.newToken());
    }

    @Transactional
    public SessionTokens register(String email, String password, String displayName, String timeZone,
            String clientIp) {
        limiter.acquire("register", clientIp, properties.registerRateLimit());

        String normalizedEmail = EmailAddresses.normalize(email);
        if (!EmailAddresses.isValid(normalizedEmail)) {
            throw ApiException.validation("email", "must be a valid e-mail address");
        }
        validatePassword(password);
        String name = displayName == null ? "" : displayName.strip();
        if (name.isEmpty() || name.length() > DISPLAY_NAME_MAX) {
            throw ApiException.validation("displayName", "must be 1-" + DISPLAY_NAME_MAX + " characters");
        }

        UserView user = users.create(normalizedEmail, passwordEncoder.encode(password), name, timeZone);
        log.info("User registered: userId={}", user.id());
        return startSession(user);
    }

    @Transactional
    public SessionTokens login(String email, String password, String clientIp) {
        limiter.acquire("login", clientIp, properties.loginRateLimit());

        String normalizedEmail = EmailAddresses.normalize(email);
        Optional<UserApi.UserCredentials> credentials = EmailAddresses.isValid(normalizedEmail)
                ? users.findCredentials(normalizedEmail)
                : Optional.empty();
        boolean passwordUsable = password != null && password.getBytes(StandardCharsets.UTF_8).length <= PASSWORD_MAX_BYTES;
        String hash = credentials.map(UserApi.UserCredentials::passwordHash).orElse(dummyHash);
        // Always run BCrypt, also for unknown e-mails, so timing does not reveal registered addresses.
        boolean matches = passwordEncoder.matches(passwordUsable ? password : "", hash);
        if (credentials.isEmpty() || !passwordUsable || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "E-mail or password is incorrect");
        }
        UserView user = users.findById(credentials.get().userId()).orElseThrow();
        log.info("User logged in: userId={}", user.id());
        return startSession(user);
    }

    /**
     * Rotates a refresh token. Not rolled back on ApiException: when a reused token revokes the
     * session, the revocation must be committed although the response is 401.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public RotatedTokens refresh(String refreshToken) {
        Instant now = clock.instant();
        SessionStore.LockedToken token = (refreshToken == null || refreshToken.isBlank())
                ? null
                : sessions.lockToken(RefreshTokens.hash(refreshToken)).orElse(null);
        if (token == null) {
            throw unauthorized("UNAUTHORIZED", "Refresh token is not valid");
        }
        if (token.sessionRevokedAt() != null) {
            throw unauthorized("SESSION_REVOKED", "Session has been revoked");
        }
        if (token.usedAt() != null) {
            Duration sinceRotation = Duration.between(token.usedAt(), now);
            if (sinceRotation.compareTo(properties.reuseGracePeriod()) <= 0) {
                // A concurrent request or a retry after a lost response: keep the session open.
                throw unauthorized("TOKEN_ALREADY_ROTATED", "Refresh token was already used; use the newer token");
            }
            sessions.revokeSession(token.sessionId(), "TOKEN_REUSE", now);
            log.warn("Refresh token reuse detected; session revoked: userId={} sessionId={}",
                    token.userId(), token.sessionId());
            throw unauthorized("TOKEN_REUSED", "Refresh token was reused; the session has been revoked");
        }
        if (!now.isBefore(token.expiresAt())) {
            throw unauthorized("TOKEN_EXPIRED", "Refresh token has expired");
        }

        sessions.markUsed(token.tokenId(), now);
        sessions.markRefreshed(token.sessionId(), now);
        String newRefreshToken = issueRefreshToken(token.sessionId(), now);
        return new RotatedTokens(accessTokens.issue(token.userId(), token.sessionId()), newRefreshToken);
    }

    /** Revokes the session; its access and refresh tokens stop working immediately. */
    @Transactional
    public void logout(UUID sessionId) {
        sessions.revokeSession(sessionId, "LOGOUT", clock.instant());
    }

    private SessionTokens startSession(UserView user) {
        Instant now = clock.instant();
        UUID sessionId = sessions.createSession(user.id(), now);
        String refreshToken = issueRefreshToken(sessionId, now);
        return new SessionTokens(accessTokens.issue(user.id(), sessionId), refreshToken, user);
    }

    private String issueRefreshToken(UUID sessionId, Instant now) {
        String token = RefreshTokens.newToken();
        sessions.insertRefreshToken(sessionId, RefreshTokens.hash(token), now.plus(properties.refreshTokenTtl()), now);
        return token;
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < PASSWORD_MIN_CHARS || password.length() > PASSWORD_MAX_CHARS) {
            throw ApiException.validation("password", "must be " + PASSWORD_MIN_CHARS + "-" + PASSWORD_MAX_CHARS + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            throw ApiException.validation("password", "must be at most " + PASSWORD_MAX_BYTES + " bytes in UTF-8");
        }
    }

    private static ApiException unauthorized(String code, String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, detail);
    }
}
