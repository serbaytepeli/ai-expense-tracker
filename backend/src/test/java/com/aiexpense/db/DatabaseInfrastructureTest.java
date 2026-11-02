package com.aiexpense.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.aiexpense.support.DatabaseIntegrationTest;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The application connects to PostgreSQL 16 and Flyway has applied the migrations of
 * docs/database-design.md section 9 that exist so far (V1-V3, T1.1).
 */
class DatabaseInfrastructureTest extends DatabaseIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Flyway flyway;

    @Test
    void connectsToPostgres16() {
        Integer major = jdbc.sql("SELECT current_setting('server_version_num')::int / 10000")
                .query(Integer.class).single();
        assertThat(major).isEqualTo(16);
    }

    @Test
    void flywayIsConfiguredForTheMigrationFolderAndHasNothingPending() {
        assertThat(flyway.getConfiguration().getLocations())
                .extracting(Object::toString)
                .containsExactly("classpath:db/migration");
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
        MigrationInfo[] pending = flyway.info().pending();
        assertThat(pending).isEmpty();
    }

    @Test
    void migrationsV1ToV5AreApplied() {
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().getVersion(), MigrationInfo::getDescription)
                .containsExactly(
                        tuple("1", "create users"),
                        tuple("2", "create auth sessions and refresh tokens"),
                        tuple("3", "create categories"),
                        tuple("4", "category name turkish case folding"),
                        tuple("5", "create expenses"));
    }

    @Test
    void exactlyTheExpectedTablesExist() {
        List<String> tables = jdbc.sql("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """).query(String.class).list();
        assertThat(tables).containsExactly("auth_sessions", "categories", "expenses", "refresh_tokens", "users");
    }
}
