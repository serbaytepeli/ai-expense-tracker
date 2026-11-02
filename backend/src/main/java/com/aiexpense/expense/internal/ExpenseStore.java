package com.aiexpense.expense.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access to the {@code expenses} table. Every statement is scoped to the owner's user_id. */
@Repository
class ExpenseStore {

    /** Filters of the expense list; null fields are not applied. */
    record Filter(LocalDate from, LocalDate to, UUID categoryId, String search) {
    }

    private static final String COLUMNS = """
            id, user_id, category_id, amount, expense_date, description, note, payment_method,
            created_at, updated_at""";

    /** Same Turkish-aware case folding as the category name index (migration V4). */
    private static final String FOLD_START = "lower(translate(";
    private static final String FOLD_END = ", 'ÇĞİIıÖŞÜ', 'çğiiiöşü'))";

    private final JdbcClient jdbc;

    ExpenseStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(ExpenseRecord e) {
        jdbc.sql("""
                INSERT INTO expenses (id, user_id, category_id, amount, expense_date, description, note,
                                      payment_method, created_at, updated_at)
                VALUES (:id, :userId, :categoryId, :amount, :date, :description, :note, :paymentMethod,
                        :createdAt, :updatedAt)""")
                .param("id", e.id())
                .param("userId", e.userId())
                .param("categoryId", e.categoryId())
                .param("amount", e.amount())
                .param("date", e.date())
                .param("description", e.description())
                .param("note", e.note())
                .param("paymentMethod", e.paymentMethod() == null ? null : e.paymentMethod().name())
                .param("createdAt", utc(e.createdAt()))
                .param("updatedAt", utc(e.updatedAt()))
                .update();
    }

    void update(ExpenseRecord e) {
        jdbc.sql("""
                UPDATE expenses
                SET category_id = :categoryId, amount = :amount, expense_date = :date,
                    description = :description, note = :note, payment_method = :paymentMethod,
                    updated_at = :updatedAt
                WHERE id = :id AND user_id = :userId""")
                .param("id", e.id())
                .param("userId", e.userId())
                .param("categoryId", e.categoryId())
                .param("amount", e.amount())
                .param("date", e.date())
                .param("description", e.description())
                .param("note", e.note())
                .param("paymentMethod", e.paymentMethod() == null ? null : e.paymentMethod().name())
                .param("updatedAt", utc(e.updatedAt()))
                .update();
    }

    /** Locks the row for the rest of the transaction, so concurrent edits are applied one by one. */
    Optional<ExpenseRecord> findForUpdate(UUID userId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM expenses WHERE id = :id AND user_id = :userId FOR UPDATE")
                .param("id", id).param("userId", userId)
                .query(ExpenseStore::map).optional();
    }

    Optional<ExpenseRecord> find(UUID userId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM expenses WHERE id = :id AND user_id = :userId")
                .param("id", id).param("userId", userId)
                .query(ExpenseStore::map).optional();
    }

    boolean delete(UUID userId, UUID id) {
        return jdbc.sql("DELETE FROM expenses WHERE id = :id AND user_id = :userId")
                .param("id", id).param("userId", userId).update() == 1;
    }

    /** Newest first: date desc, then createdAt desc (id breaks remaining ties so pages are stable). */
    List<ExpenseRecord> list(UUID userId, Filter filter, int size, long offset) {
        Map<String, Object> params = new TreeMap<>();
        String sql = "SELECT " + COLUMNS + " FROM expenses " + where(userId, filter, params)
                + " ORDER BY expense_date DESC, created_at DESC, id DESC LIMIT :limit OFFSET :offset";
        params.put("limit", size);
        params.put("offset", offset);
        return jdbc.sql(sql).params(params).query(ExpenseStore::map).list();
    }

    long count(UUID userId, Filter filter) {
        Map<String, Object> params = new TreeMap<>();
        return jdbc.sql("SELECT count(*) FROM expenses " + where(userId, filter, params))
                .params(params).query(Long.class).single();
    }

    // Only fixed SQL fragments are concatenated; every value is a bind parameter.
    private static String where(UUID userId, Filter filter, Map<String, Object> params) {
        StringBuilder where = new StringBuilder("WHERE user_id = :userId");
        params.put("userId", userId);
        if (filter.from() != null) {
            where.append(" AND expense_date >= :from");
            params.put("from", filter.from());
        }
        if (filter.to() != null) {
            where.append(" AND expense_date <= :to");
            params.put("to", filter.to());
        }
        if (filter.categoryId() != null) {
            where.append(" AND category_id = :categoryId");
            params.put("categoryId", filter.categoryId());
        }
        if (filter.search() != null) {
            where.append(" AND ").append(FOLD_START).append("description").append(FOLD_END)
                    .append(" LIKE '%' || ").append(FOLD_START).append(":search").append(FOLD_END)
                    .append(" || '%' ESCAPE '\\'");
            params.put("search", escapeLike(filter.search()));
        }
        return where.toString();
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static ExpenseRecord map(ResultSet rs, int row) throws SQLException {
        String method = rs.getString("payment_method");
        return new ExpenseRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("category_id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getObject("expense_date", LocalDate.class),
                rs.getString("description"),
                rs.getString("note"),
                method == null ? null : PaymentMethod.valueOf(method),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(java.time.Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
