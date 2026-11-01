package com.aiexpense.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiexpense.support.DatabaseIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Constraints of migrations V1-V3 (docs/database-design.md sections 6 and 10) on a real
 * PostgreSQL 16 database. Every test runs in a transaction that is rolled back, so no data is
 * left behind. Violations are checked by constraint name, so a test cannot pass because of an
 * unrelated error.
 */
@Transactional
class IdentityAndCategorySchemaTest extends DatabaseIntegrationTest {

    private static final String NOW = "2026-11-01T10:00:00Z";
    // A value in the format of a SHA-256 hex digest; not derived from any real token.
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Autowired
    JdbcClient jdbc;

    // --- users ------------------------------------------------------------------------------

    @Test
    void userDefaultsAreTryIstanbulAndTurkish() {
        UUID id = insertUser("defaults@example.com");

        var row = jdbc.sql("SELECT currency, time_zone, locale FROM users WHERE id = :id")
                .param("id", id).query().singleRow();
        assertThat(row).containsEntry("currency", "TRY")
                .containsEntry("time_zone", "Europe/Istanbul")
                .containsEntry("locale", "tr-TR");
    }

    @Test
    void emailIsUnique() {
        insertUser("same@example.com");

        assertViolation(() -> insertUser("same@example.com"), "uk_users_email");
    }

    @Test
    void emailLongerThan254CharactersIsRejected() {
        String email = "a".repeat(243) + "@example.com"; // 255 characters

        assertThatThrownBy(() -> insertUser(email)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("character varying(254)");
    }

    @Test
    void passwordHashIsRequired() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO users (email, password_hash, display_name, created_at, updated_at)
                VALUES ('nohash@example.com', NULL, 'Ali', :now::timestamptz, :now::timestamptz)
                """).param("now", NOW).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("password_hash");
    }

    // --- auth_sessions / refresh_tokens ------------------------------------------------------

    @Test
    void sessionRequiresAnExistingUser() {
        assertViolation(() -> insertSession(UUID.randomUUID()), "fk_auth_sessions_user");
    }

    @Test
    void revokeReasonMustBeAKnownValue() {
        UUID user = insertUser("reason@example.com");
        UUID session = insertSession(user);

        assertViolation(() -> jdbc.sql("""
                UPDATE auth_sessions SET revoked_at = :now::timestamptz, revoke_reason = 'STOLEN'
                WHERE id = :id
                """).param("now", NOW).param("id", session).update(), "ck_auth_sessions_revoke_reason");
    }

    @Test
    void everyDocumentedRevokeReasonIsAccepted() {
        UUID user = insertUser("reasons@example.com");
        for (String reason : new String[] {"LOGOUT", "TOKEN_REUSE", "PASSWORD_CHANGE", "ACCOUNT_DELETED", "EXPIRED"}) {
            UUID session = insertSession(user);
            int updated = jdbc.sql("""
                    UPDATE auth_sessions SET revoked_at = :now::timestamptz, revoke_reason = :reason
                    WHERE id = :id
                    """).param("now", NOW).param("reason", reason).param("id", session).update();
            assertThat(updated).isOne();
        }
    }

    @Test
    void refreshTokenHashIsUnique() {
        UUID session = insertSession(insertUser("hash@example.com"));
        insertRefreshToken(session, HASH_A);

        assertViolation(() -> insertRefreshToken(session, HASH_A), "uk_refresh_tokens_token_hash");
    }

    @Test
    void refreshTokenRequiresAnExistingSession() {
        assertViolation(() -> insertRefreshToken(UUID.randomUUID(), HASH_A), "fk_refresh_tokens_session");
    }

    @Test
    void refreshTokenStoresOnlyAFixedLengthHash() {
        UUID session = insertSession(insertUser("length@example.com"));

        assertThatThrownBy(() -> insertRefreshToken(session, HASH_A + "x"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("character(64)");
    }

    // --- categories --------------------------------------------------------------------------

    @Test
    void categoryRequiresAnExistingUser() {
        assertViolation(() -> insertCategory(UUID.randomUUID(), "Market", "#4CAF50", null), "fk_categories_user");
    }

    @Test
    void activeCategoryNamesAreUniquePerUserIgnoringCase() {
        UUID user = insertUser("names@example.com");
        insertCategory(user, "Market", "#4CAF50", null);

        assertViolation(() -> insertCategory(user, "MARKET", "#4CAF50", null), "uk_categories_user_active_name");
    }

    @Test
    void archivedCategoryNameCanBeReused() {
        UUID user = insertUser("archive@example.com");
        UUID first = insertCategory(user, "Market", "#4CAF50", null);
        jdbc.sql("UPDATE categories SET archived_at = :now::timestamptz WHERE id = :id")
                .param("now", NOW).param("id", first).update();

        UUID second = insertCategory(user, "Market", "#4CAF50", null);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void sameCategoryNameIsAllowedForDifferentUsers() {
        insertCategory(insertUser("u1@example.com"), "Market", "#4CAF50", null);

        UUID other = insertCategory(insertUser("u2@example.com"), "Market", "#4CAF50", null);
        assertThat(other).isNotNull();
    }

    @Test
    void systemKeyIsUniquePerUser() {
        UUID user = insertUser("system@example.com");
        insertCategory(user, "Market", "#4CAF50", "GROCERIES");

        assertViolation(() -> insertCategory(user, "Bakkal", "#4CAF50", "GROCERIES"), "uk_categories_user_system_key");
    }

    @Test
    void blankCategoryNameIsRejected() {
        UUID user = insertUser("blank@example.com");

        assertViolation(() -> insertCategory(user, "   ", "#4CAF50", null), "ck_categories_name_length");
    }

    @Test
    void colorMustBeHexRgb() {
        UUID user = insertUser("color@example.com");

        assertViolation(() -> insertCategory(user, "Market", "#GGGGGG", null), "ck_categories_color");
        // CHAR(7) pads a short value with spaces, which the pattern also rejects.
    }

    @Test
    void otherCategoryCannotBeArchived() {
        UUID user = insertUser("other@example.com");
        UUID other = insertCategory(user, "Diğer", "#9E9E9E", "OTHER");

        assertViolation(() -> jdbc.sql("UPDATE categories SET archived_at = :now::timestamptz WHERE id = :id")
                .param("now", NOW).param("id", other).update(), "ck_categories_other_not_archived");
    }

    @Test
    void nonOtherSystemCategoryCanBeArchived() {
        UUID user = insertUser("archsys@example.com");
        UUID groceries = insertCategory(user, "Market", "#4CAF50", "GROCERIES");

        int updated = jdbc.sql("UPDATE categories SET archived_at = :now::timestamptz WHERE id = :id")
                .param("now", NOW).param("id", groceries).update();
        assertThat(updated).isOne();
    }

    @Test
    void categoryIdAndUserIdPairIsUniqueForTheCompositeForeignKey() {
        Integer count = jdbc.sql("""
                SELECT count(*) FROM pg_constraint
                WHERE conname = 'uk_categories_id_user' AND contype = 'u'
                """).query(Integer.class).single();
        assertThat(count).isOne();
    }

    // --- cascade on account deletion ----------------------------------------------------------

    @Test
    void deletingAUserRemovesAllOfItsRowsAndNothingElse() {
        UUID deleted = insertUser("delete-me@example.com");
        UUID deletedSession = insertSession(deleted);
        insertRefreshToken(deletedSession, HASH_A);
        insertCategory(deleted, "Market", "#4CAF50", "GROCERIES");

        UUID kept = insertUser("keep-me@example.com");
        insertRefreshToken(insertSession(kept), HASH_B);
        insertCategory(kept, "Market", "#4CAF50", "GROCERIES");

        jdbc.sql("DELETE FROM users WHERE id = :id").param("id", deleted).update();

        assertThat(countFor("users", "id", deleted)).isZero();
        assertThat(countFor("auth_sessions", "user_id", deleted)).isZero();
        assertThat(countFor("refresh_tokens", "session_id", deletedSession)).isZero();
        assertThat(countFor("categories", "user_id", deleted)).isZero();

        assertThat(countFor("users", "id", kept)).isOne();
        assertThat(countFor("auth_sessions", "user_id", kept)).isOne();
        assertThat(countFor("categories", "user_id", kept)).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM refresh_tokens WHERE token_hash = :h")
                .param("h", HASH_B).query(Integer.class).single()).isOne();
    }

    // --- helpers -------------------------------------------------------------------------------

    private UUID insertUser(String email) {
        return jdbc.sql("""
                INSERT INTO users (email, password_hash, display_name, created_at, updated_at)
                VALUES (:email, :hash, 'Test', :now::timestamptz, :now::timestamptz)
                RETURNING id
                """)
                .param("email", email)
                // Shape of a BCrypt hash; not the hash of any real password.
                .param("hash", "$2a$12$" + "x".repeat(53))
                .param("now", NOW)
                .query(UUID.class).single();
    }

    private UUID insertSession(UUID userId) {
        return jdbc.sql("""
                INSERT INTO auth_sessions (user_id, created_at) VALUES (:user, :now::timestamptz) RETURNING id
                """).param("user", userId).param("now", NOW).query(UUID.class).single();
    }

    private void insertRefreshToken(UUID sessionId, String hash) {
        jdbc.sql("""
                INSERT INTO refresh_tokens (session_id, token_hash, expires_at, created_at)
                VALUES (:session, :hash, :now::timestamptz + interval '30 days', :now::timestamptz)
                """).param("session", sessionId).param("hash", hash).param("now", NOW).update();
    }

    private UUID insertCategory(UUID userId, String name, String color, String systemKey) {
        return jdbc.sql("""
                INSERT INTO categories (user_id, name, icon, color, system_key, created_at, updated_at)
                VALUES (:user, :name, 'cart', :color, :key, :now::timestamptz, :now::timestamptz)
                RETURNING id
                """)
                .param("user", userId).param("name", name).param("color", color)
                .param("key", systemKey).param("now", NOW)
                .query(UUID.class).single();
    }

    private int countFor(String table, String column, UUID value) {
        // Table and column names come from this class only, never from input.
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = :v")
                .param("v", value).query(Integer.class).single();
    }

    private static void assertViolation(Runnable statement, String constraintName) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraintName);
    }
}
