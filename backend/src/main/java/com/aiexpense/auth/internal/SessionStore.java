package com.aiexpense.auth.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for {@code auth_sessions} and {@code refresh_tokens} (migration V2). Plain SQL because the
 * refresh flow needs row locks and conditional updates (docs/architecture.md section 3.6).
 */
@Repository
class SessionStore {

    /** A refresh token row together with the state of its session, read under a row lock. */
    record LockedToken(UUID tokenId, UUID sessionId, UUID userId, Instant expiresAt, Instant usedAt,
            Instant sessionRevokedAt) {
    }

    private final JdbcClient jdbc;

    SessionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID createSession(UUID userId, Instant now) {
        return jdbc.sql("""
                INSERT INTO auth_sessions (id, user_id, created_at) VALUES (:id, :user, :now) RETURNING id
                """)
                .param("id", UUID.randomUUID()).param("user", userId).param("now", ts(now))
                .query(UUID.class).single();
    }

    void insertRefreshToken(UUID sessionId, String tokenHash, Instant expiresAt, Instant now) {
        jdbc.sql("""
                INSERT INTO refresh_tokens (id, session_id, token_hash, expires_at, created_at)
                VALUES (:id, :session, :hash, :expires, :now)
                """)
                .param("id", UUID.randomUUID()).param("session", sessionId).param("hash", tokenHash)
                .param("expires", ts(expiresAt)).param("now", ts(now))
                .update();
    }

    /**
     * Reads a token by hash and locks its row until the transaction ends, so two concurrent
     * refreshes with the same token are serialized: the second one sees {@code used_at} set.
     */
    Optional<LockedToken> lockToken(String tokenHash) {
        return jdbc.sql("""
                SELECT rt.id, rt.session_id, s.user_id, rt.expires_at, rt.used_at, s.revoked_at
                FROM refresh_tokens rt
                JOIN auth_sessions s ON s.id = rt.session_id
                WHERE rt.token_hash = :hash
                FOR UPDATE OF rt
                """)
                .param("hash", tokenHash)
                .query((rs, row) -> new LockedToken(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class),
                        instant(rs.getObject(4, OffsetDateTime.class)),
                        instant(rs.getObject(5, OffsetDateTime.class)),
                        instant(rs.getObject(6, OffsetDateTime.class))))
                .optional();
    }

    void markUsed(UUID tokenId, Instant now) {
        jdbc.sql("UPDATE refresh_tokens SET used_at = :now WHERE id = :id AND used_at IS NULL")
                .param("now", ts(now)).param("id", tokenId).update();
    }

    void markRefreshed(UUID sessionId, Instant now) {
        jdbc.sql("UPDATE auth_sessions SET last_refreshed_at = :now WHERE id = :id")
                .param("now", ts(now)).param("id", sessionId).update();
    }

    /** Revokes an active session; returns false if it was already revoked or does not exist. */
    boolean revokeSession(UUID sessionId, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE auth_sessions SET revoked_at = :now, revoke_reason = :reason
                WHERE id = :id AND revoked_at IS NULL
                """)
                .param("now", ts(now)).param("reason", reason).param("id", sessionId)
                .update() == 1;
    }

    /** True if the session exists, belongs to the user and is not revoked (checked on every request). */
    boolean isActive(UUID sessionId, UUID userId) {
        return jdbc.sql("""
                SELECT count(*) FROM auth_sessions WHERE id = :id AND user_id = :user AND revoked_at IS NULL
                """)
                .param("id", sessionId).param("user", userId)
                .query(Integer.class).single() == 1;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
