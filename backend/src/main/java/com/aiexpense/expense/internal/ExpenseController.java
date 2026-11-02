package com.aiexpense.expense.internal;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.aiexpense.common.ApiException;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/api-contract.md section 5. The owner is always the JWT subject, never a request field. */
@RestController
@RequestMapping(path = ExpenseController.BASE, produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "401", description = "UNAUTHORIZED, TOKEN_EXPIRED or SESSION_REVOKED", content = @Content(mediaType = ExpenseController.PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
class ExpenseController {

    static final String BASE = "/api/v1/expenses";
    static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;
    /** database-design.md section 2: at most 12 integer and 2 decimal digits; no sign, exponent or comma. */
    static final String AMOUNT_PATTERN = "^[0-9]{1,12}(\\.[0-9]{1,2})?$";
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;
    static final int MAX_PAGE = 1_000_000;
    static final int SEARCH_MIN = 2;
    static final int SEARCH_MAX = 140;

    // Text lengths are checked after trimming in ExpenseService; the size limits here only cap input.
    record ExpenseRequest(
            @NotNull @Pattern(regexp = AMOUNT_PATTERN, message = "must be a positive amount with at most 12 integer and 2 decimal digits, e.g. \"149.90\"")
            @JsonDeserialize(using = JsonStringOnly.class)
            @Schema(requiredMode = REQUIRED, example = "149.90", description = "JSON string, never a number") String amount,
            @NotNull @Schema(requiredMode = REQUIRED, description = "Not after today in the user's time zone") LocalDate date,
            @NotNull @Schema(requiredMode = REQUIRED) UUID categoryId,
            @Size(max = 1000) @Schema(types = {"string", "null"}, description = "Trimmed; at most 140 characters; blank = null") String description,
            @Size(max = 2000) @Schema(types = {"string", "null"}, description = "Trimmed; at most 500 characters; blank = null") String note,
            @Schema(types = {"string", "null"}, allowableValues = {"CASH", "CARD", "OTHER"}) PaymentMethod paymentMethod) {
        @Override
        public String toString() {
            return "ExpenseRequest[categoryId=" + categoryId + "]";
        }
    }

    record ExpenseCategory(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String icon,
            @Schema(requiredMode = REQUIRED) String color,
            @Schema(requiredMode = REQUIRED) boolean archived) {
    }

    record ExpenseResponse(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED, example = "149.90") String amount,
            @Schema(requiredMode = REQUIRED, example = "TRY") String currency,
            @Schema(requiredMode = REQUIRED) LocalDate date,
            @Schema(requiredMode = REQUIRED) ExpenseCategory category,
            @Schema(requiredMode = REQUIRED, types = {"string", "null"}) String description,
            @Schema(requiredMode = REQUIRED, types = {"string", "null"}) String note,
            @Schema(requiredMode = REQUIRED, types = {"string", "null"}, allowableValues = {"CASH", "CARD", "OTHER"}) PaymentMethod paymentMethod,
            @Schema(requiredMode = REQUIRED) Instant createdAt,
            @Schema(requiredMode = REQUIRED) Instant updatedAt) {
        @Override
        public String toString() {
            return "ExpenseResponse[id=" + id + "]";
        }
    }

    record ExpensePageResponse(
            @Schema(requiredMode = REQUIRED) List<ExpenseResponse> items,
            @Schema(requiredMode = REQUIRED) int page,
            @Schema(requiredMode = REQUIRED) int size,
            @Schema(requiredMode = REQUIRED) long totalItems,
            @Schema(requiredMode = REQUIRED) int totalPages) {
    }

    private final ExpenseService expenses;

    ExpenseController(ExpenseService expenses) {
        this.expenses = expenses;
    }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Newest first (date desc, then createdAt desc)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    ExpensePageResponse list(@AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "YYYY-MM; not together with from/to") @RequestParam(required = false) String month,
            @Parameter(description = "YYYY-MM-DD, inclusive; together with to") @RequestParam(required = false) String from,
            @Parameter(description = "YYYY-MM-DD, inclusive; together with from") @RequestParam(required = false) String to,
            @RequestParam(required = false) String categoryId,
            @Parameter(description = "Text search in the description, at least 2 characters") @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size) {
        LocalDate fromDate = date("from", from);
        LocalDate toDate = date("to", to);
        if (month != null && (from != null || to != null)) {
            throw ApiException.validation("month", "use either month or from/to");
        }
        if (month != null) {
            YearMonth yearMonth = month("month", month);
            fromDate = yearMonth.atDay(1);
            toDate = yearMonth.atEndOfMonth();
        } else if ((fromDate == null) != (toDate == null)) {
            throw ApiException.validation(fromDate == null ? "from" : "to", "from and to must be given together");
        } else if (fromDate != null && fromDate.isAfter(toDate)) {
            throw ApiException.validation("from", "must not be after to");
        }
        var filter = new ExpenseStore.Filter(fromDate, toDate, uuid("categoryId", categoryId), search(q));
        var result = expenses.list(userId(jwt), filter, integer("page", page, 0, MAX_PAGE),
                integer("size", size, 1, MAX_SIZE));
        return new ExpensePageResponse(result.items().stream().map(ExpenseController::toResponse).toList(),
                result.page(), result.size(), result.totalItems(), result.totalPages());
    }

    @GetMapping("/{id}")
    @ApiResponse(responseCode = "200", description = "The expense")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    ExpenseResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return toResponse(expenses.get(userId(jwt), expenseId(id)));
    }

    @PostMapping
    @ApiResponse(responseCode = "201", description = "Created; Location points to the new expense")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (category)", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "CATEGORY_ARCHIVED", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<ExpenseResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ExpenseRequest body) {
        ExpenseResponse created = toResponse(expenses.create(userId(jwt), toInput(body)));
        return ResponseEntity.created(URI.create(BASE + "/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @ApiResponse(responseCode = "200", description = "Replaced")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (expense or category)", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "CATEGORY_ARCHIVED", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    ExpenseResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody ExpenseRequest body) {
        return toResponse(expenses.update(userId(jwt), expenseId(id), toInput(body)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Deleted permanently")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        expenses.delete(userId(jwt), expenseId(id));
    }

    private static ExpenseService.ExpenseInput toInput(ExpenseRequest body) {
        return new ExpenseService.ExpenseInput(body.amount(), body.date(), body.categoryId(), body.description(),
                body.note(), body.paymentMethod());
    }

    private static ExpenseResponse toResponse(ExpenseService.ExpenseView view) {
        ExpenseRecord e = view.expense();
        var c = view.category();
        return new ExpenseResponse(e.id(), money(e.amount()), view.currency(), e.date(),
                new ExpenseCategory(c.id(), c.name(), c.icon(), c.color(), c.archived()),
                e.description(), e.note(), e.paymentMethod(), e.createdAt(), e.updatedAt());
    }

    /** JSON money: string with exactly two decimals (database-design.md section 2). */
    static String money(BigDecimal amount) {
        return amount.setScale(2).toPlainString();
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /** A malformed ID can never name an existing expense: same 404 as an unknown one. */
    private static UUID expenseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ExpenseService.notFound();
        }
    }

    private static LocalDate date(String field, String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.validation(field, "must be a date in YYYY-MM-DD format");
        }
    }

    private static YearMonth month(String field, String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.validation(field, "must be a month in YYYY-MM format");
        }
    }

    private static UUID uuid(String field, String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(field, "must be a UUID");
        }
    }

    private static String search(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String trimmed = q.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < SEARCH_MIN || length > SEARCH_MAX) {
            throw ApiException.validation("q", "must be " + SEARCH_MIN + "-" + SEARCH_MAX + " characters");
        }
        return trimmed;
    }

    private static int integer(String field, String value, int min, int max) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw ApiException.validation(field, "must be an integer between " + min + " and " + max);
    }
}
