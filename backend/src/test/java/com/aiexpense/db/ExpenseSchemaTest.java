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
 * Constraints of migration V5 (docs/database-design.md, expenses) on a real PostgreSQL 16
 * database. Same isolation as IdentityAndCategorySchemaTest: one rolled-back transaction per
 * test, a SAVEPOINT around every expected violation.
 */
@Transactional
class ExpenseSchemaTest extends DatabaseIntegrationTest {

    private static final String NOW = "2026-11-01T10:00:00Z";

    @Autowired
    JdbcClient jdbc;

    @Test
    void validExpenseIsStoredWithExactAmount() {
        UUID user = insertUser("valid@example.com");
        UUID category = insertCategory(user);

        UUID id = insertExpense(user, category, "999999999999.99", "CARD");
        assertThat(jdbc.sql("SELECT amount::text FROM expenses WHERE id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("999999999999.99");
    }

    @Test
    void categoryMustBelongToTheSameUser() {
        UUID owner = insertUser("owner@example.com");
        UUID other = insertUser("other@example.com");
        UUID othersCategory = insertCategory(other);

        assertRejected(() -> insertExpense(owner, othersCategory, "10.00", null), "fk_expenses_category");
        assertRejected(() -> insertExpense(owner, UUID.randomUUID(), "10.00", null), "fk_expenses_category");
    }

    @Test
    void amountMustBePositiveAndFitNumeric14_2() {
        UUID user = insertUser("amount@example.com");
        UUID category = insertCategory(user);

        assertRejected(() -> insertExpense(user, category, "0", null), "ck_expenses_amount_positive");
        assertRejected(() -> insertExpense(user, category, "-1", null), "ck_expenses_amount_positive");
        assertRejected(() -> insertExpense(user, category, "1000000000000.00", null), "numeric field overflow");
    }

    @Test
    void paymentMethodIsRestricted() {
        UUID user = insertUser("payment@example.com");
        UUID category = insertCategory(user);

        assertRejected(() -> insertExpense(user, category, "1.00", "BITCOIN"), "ck_expenses_payment_method");
        assertThat(insertExpense(user, category, "1.00", null)).isNotNull();
    }

    @Test
    void futureDatesAreNotADatabaseRule() {
        // database-design.md section 1: "not in the future" is checked only in the service layer.
        UUID user = insertUser("future@example.com");
        UUID category = insertCategory(user);
        assertThat(jdbc.sql("""
                INSERT INTO expenses (user_id, category_id, amount, expense_date, created_at, updated_at)
                VALUES (:u, :c, 1, DATE '2999-01-01', :now::timestamptz, :now::timestamptz) RETURNING id""")
                .param("u", user).param("c", category).param("now", NOW).query(UUID.class).single()).isNotNull();
    }

    @Test
    void deletingTheUserDeletesTheirExpenses() {
        UUID user = insertUser("cascade@example.com");
        insertExpense(user, insertCategory(user), "5.00", null);

        jdbc.sql("DELETE FROM users WHERE id = :id").param("id", user).update();
        assertThat(jdbc.sql("SELECT count(*) FROM expenses WHERE user_id = :id").param("id", user)
                .query(Integer.class).single()).isZero();
    }

    private UUID insertUser(String email) {
        return jdbc.sql("""
                INSERT INTO users (email, password_hash, display_name, created_at, updated_at)
                VALUES (:email, :hash, 'Test', :now::timestamptz, :now::timestamptz) RETURNING id""")
                // Shape of a BCrypt hash; not the hash of any real password.
                .param("email", email).param("hash", "$2a$12$" + "x".repeat(53)).param("now", NOW)
                .query(UUID.class).single();
    }

    private UUID insertCategory(UUID user) {
        return jdbc.sql("""
                INSERT INTO categories (user_id, name, icon, color, created_at, updated_at)
                VALUES (:u, :name, 'cart', '#000000', :now::timestamptz, :now::timestamptz) RETURNING id""")
                .param("u", user).param("name", "C-" + UUID.randomUUID().toString().substring(0, 8))
                .param("now", NOW).query(UUID.class).single();
    }

    private UUID insertExpense(UUID user, UUID category, String amount, String paymentMethod) {
        return jdbc.sql("""
                INSERT INTO expenses (user_id, category_id, amount, expense_date, payment_method, created_at,
                                      updated_at)
                VALUES (:u, :c, CAST(:amount AS numeric), DATE '2026-10-28', :pm, :now::timestamptz,
                        :now::timestamptz) RETURNING id""")
                .param("u", user).param("c", category).param("amount", amount).param("pm", paymentMethod)
                .param("now", NOW).query(UUID.class).single();
    }

    private void assertRejected(Runnable statement, String expected) {
        jdbc.sql("SAVEPOINT expected_violation").update();
        try {
            assertThatThrownBy(statement::run)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining(expected);
        } finally {
            jdbc.sql("ROLLBACK TO SAVEPOINT expected_violation").update();
            jdbc.sql("RELEASE SAVEPOINT expected_violation").update();
        }
    }
}
