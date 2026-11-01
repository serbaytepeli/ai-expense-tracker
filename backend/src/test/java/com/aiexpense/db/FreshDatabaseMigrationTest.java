package com.aiexpense.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiexpense.support.DatabaseIntegrationTest;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * docs/database-design.md section 10: all migrations apply, in order, to an EMPTY database and
 * Flyway validation passes. Uses a throwaway schema so the shared test schema is not touched.
 */
class FreshDatabaseMigrationTest extends DatabaseIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Test
    void allMigrationsApplyInOrderToAnEmptySchema() {
        String schema = "fresh_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .locations("classpath:db/migration")
                    .load();

            MigrateResult result = flyway.migrate();

            assertThat(result.success).isTrue();
            assertThat(result.initialSchemaVersion).isNull();
            assertThat(result.migrations).extracting(migration -> migration.version)
                    .containsExactly("1", "2", "3");
            flyway.validate();

            List<String> tables = jdbc.sql("""
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = :schema AND table_name <> 'flyway_schema_history'
                    ORDER BY table_name
                    """).param("schema", schema).query(String.class).list();
            assertThat(tables).containsExactly("auth_sessions", "categories", "refresh_tokens", "users");
        } finally {
            // The schema name is generated above, never taken from input.
            jdbc.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
        }
    }
}
