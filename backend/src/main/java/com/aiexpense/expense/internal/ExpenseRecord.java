package com.aiexpense.expense.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Row of the {@code expenses} table (migration V5). */
record ExpenseRecord(UUID id, UUID userId, UUID categoryId, BigDecimal amount, LocalDate date, String description,
        String note, PaymentMethod paymentMethod, Instant createdAt, Instant updatedAt) {

    @Override
    public String toString() {
        // Description and note are personal text: never logged.
        return "ExpenseRecord[id=" + id + "]";
    }
}
