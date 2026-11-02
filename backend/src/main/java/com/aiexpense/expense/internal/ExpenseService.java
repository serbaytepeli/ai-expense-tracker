package com.aiexpense.expense.internal;

import com.aiexpense.category.CategoryApi;
import com.aiexpense.category.CategoryApi.CategorySummary;
import com.aiexpense.common.ApiException;
import com.aiexpense.user.UserApi;
import com.aiexpense.user.UserApi.UserView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expense rules (docs/api-contract.md section 5, FR-2). Every method takes the owner's ID, which
 * the controller reads from the JWT; expenses and categories of other users are not found.
 */
@Service
class ExpenseService {

    /** Body of POST and PUT, already checked by Bean Validation. */
    record ExpenseInput(String amount, LocalDate date, UUID categoryId, String description, String note,
            PaymentMethod paymentMethod) {
        @Override
        public String toString() {
            return "ExpenseInput[categoryId=" + categoryId + "]";
        }
    }

    /** An expense with the details the API returns. */
    record ExpenseView(ExpenseRecord expense, String currency, CategorySummary category) {
    }

    record Page(List<ExpenseView> items, int page, int size, long totalItems, int totalPages) {
    }

    static final int DESCRIPTION_MAX = 140;
    static final int NOTE_MAX = 500;
    static final BigDecimal AMOUNT_MAX = new BigDecimal("999999999999.99");

    private static final Logger log = LoggerFactory.getLogger(ExpenseService.class);

    private final ExpenseStore expenses;
    private final CategoryApi categories;
    private final UserApi users;
    private final Clock clock;

    ExpenseService(ExpenseStore expenses, CategoryApi categories, UserApi users, Clock clock) {
        this.expenses = expenses;
        this.categories = categories;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    ExpenseView create(UUID userId, ExpenseInput input) {
        UserView user = user(userId);
        BigDecimal amount = parseAmount(input.amount());
        checkNotInFuture(input.date(), user);
        CategorySummary category = activeCategory(userId, input.categoryId());
        Instant now = now();
        ExpenseRecord expense = new ExpenseRecord(UUID.randomUUID(), userId, category.id(), amount, input.date(),
                optionalText(input.description(), "description", DESCRIPTION_MAX),
                optionalText(input.note(), "note", NOTE_MAX), input.paymentMethod(), now, now);
        expenses.insert(expense);
        log.info("Expense created: expenseId={}, userId={}", expense.id(), userId);
        return new ExpenseView(expense, user.currency(), category);
    }

    @Transactional(readOnly = true)
    ExpenseView get(UUID userId, UUID expenseId) {
        ExpenseRecord expense = expenses.find(userId, expenseId).orElseThrow(ExpenseService::notFound);
        CategorySummary category = categories.findForUser(userId, expense.categoryId())
                .orElseThrow(() -> new IllegalStateException("Category of expense " + expense.id() + " not found"));
        return new ExpenseView(expense, user(userId).currency(), category);
    }

    /**
     * Full replacement (PUT). An expense whose category was archived can still be edited as long
     * as the category stays the same; a different category must be active.
     */
    @Transactional
    ExpenseView update(UUID userId, UUID expenseId, ExpenseInput input) {
        UserView user = user(userId);
        ExpenseRecord current = expenses.findForUpdate(userId, expenseId).orElseThrow(ExpenseService::notFound);
        BigDecimal amount = parseAmount(input.amount());
        checkNotInFuture(input.date(), user);
        CategorySummary category = input.categoryId().equals(current.categoryId())
                ? categories.findForUser(userId, current.categoryId()).orElseThrow(ExpenseService::categoryNotFound)
                : activeCategory(userId, input.categoryId());
        ExpenseRecord updated = new ExpenseRecord(current.id(), userId, category.id(), amount, input.date(),
                optionalText(input.description(), "description", DESCRIPTION_MAX),
                optionalText(input.note(), "note", NOTE_MAX), input.paymentMethod(), current.createdAt(), now());
        expenses.update(updated);
        return new ExpenseView(updated, user.currency(), category);
    }

    @Transactional
    void delete(UUID userId, UUID expenseId) {
        if (!expenses.delete(userId, expenseId)) {
            throw notFound();
        }
        log.info("Expense deleted: expenseId={}, userId={}", expenseId, userId);
    }

    @Transactional(readOnly = true)
    Page list(UUID userId, ExpenseStore.Filter filter, int page, int size) {
        long total = expenses.count(userId, filter);
        List<ExpenseRecord> rows = expenses.list(userId, filter, size, (long) page * size);
        String currency = user(userId).currency();
        Map<UUID, CategorySummary> byId = categories.listForUser(userId).stream()
                .collect(Collectors.toMap(CategorySummary::id, Function.identity()));
        List<ExpenseView> items = rows.stream()
                .map(e -> new ExpenseView(e, currency, Objects.requireNonNull(byId.get(e.categoryId()))))
                .toList();
        int totalPages = (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size);
        return new Page(items, page, size, total, totalPages);
    }

    /**
     * The format (at most 12 integer and 2 decimal digits, no sign or exponent) is already checked
     * by the request pattern, so the value is exact: it is only scaled, never rounded.
     */
    static BigDecimal parseAmount(String amount) {
        BigDecimal value = new BigDecimal(amount).setScale(2);
        if (value.signum() <= 0) {
            throw ApiException.validation("amount", "must be greater than 0");
        }
        if (value.compareTo(AMOUNT_MAX) > 0) {
            throw ApiException.validation("amount", "must be at most " + AMOUNT_MAX.toPlainString());
        }
        return value;
    }

    /** "Today" in the user's own time zone (D-7); checked here, never as a database constraint. */
    private void checkNotInFuture(LocalDate date, UserView user) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneId.of(user.timeZone()));
        if (date.isAfter(today)) {
            throw ApiException.validation("date", "must not be in the future");
        }
    }

    private CategorySummary activeCategory(UUID userId, UUID categoryId) {
        CategorySummary category = categories.findForUser(userId, categoryId)
                .orElseThrow(ExpenseService::categoryNotFound);
        if (category.archived()) {
            throw new ApiException(HttpStatus.CONFLICT, "CATEGORY_ARCHIVED",
                    "Archived categories cannot be used for new or moved expenses");
        }
        return category;
    }

    /** Trimmed; blank becomes null. Length in characters (code points) as PostgreSQL counts. */
    static String optionalText(String value, String field, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > max) {
            throw ApiException.validation(field, "must be at most " + max + " characters");
        }
        return trimmed;
    }

    private UserView user(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    }

    private Instant now() {
        // Microseconds: what PostgreSQL stores, so a created expense reads back unchanged.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Expense not found");
    }

    private static ApiException categoryNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Category not found");
    }
}
