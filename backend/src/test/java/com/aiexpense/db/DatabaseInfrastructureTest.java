package com.aiexpense.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiexpense.support.DatabaseIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The application connects to PostgreSQL 16 and Flyway is active. T0.2 ships no migrations:
 * the first ones (V1-V3) arrive with T1.1 (docs/database-design.md section 9).
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
    void noApplicationTablesExistYet() {
        Integer tables = jdbc.sql("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name <> 'flyway_schema_history'
                """).query(Integer.class).single();
        assertThat(tables).isZero();
    }
}
