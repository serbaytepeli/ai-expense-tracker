package com.aiexpense.category.internal;

import com.aiexpense.category.CategoryApi;
import com.aiexpense.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Category rules (docs/api-contract.md section 4). Every method takes the owner's ID, which the
 * controller reads from the JWT; categories of other users are reported as not found.
 */
@Service
class CategoryService implements CategoryApi {

    /** One edit of a category; {@code null} fields stay unchanged. */
    record Changes(String name, String icon, String color, Integer sortOrder) {
    }

    static final int NAME_MAX = 30;
    static final int SORT_ORDER_MAX = Short.MAX_VALUE;
    static final String ACTIVE_NAME_CONSTRAINT = "uk_categories_user_active_name";
    /** First key of the advisory lock that serializes sortOrder assignment (see CategoryRepository). */
    static final int SORT_ORDER_LOCK_CLASS = 0x43415431;

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    private final CategoryRepository categories;
    private final Clock clock;

    CategoryService(CategoryRepository categories, Clock clock) {
        this.categories = categories;
        this.clock = clock;
    }

    // Joins the registration transaction: the user and the categories are created together or not at all.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void createDefaultCategories(UUID userId) {
        Instant now = clock.instant();
        List<CategoryEntity> defaults = DefaultCategories.ALL.stream()
                .map(d -> CategoryEntity.create(userId, d.name(), d.icon(), d.color(), d.systemKey(),
                        (short) d.sortOrder(), now))
                .toList();
        categories.saveAllAndFlush(defaults);
    }

    @Transactional(readOnly = true)
    List<CategoryEntity> list(UUID userId, boolean includeArchived) {
        return categories.findForUser(userId, includeArchived);
    }

    @Transactional
    CategoryEntity create(UUID userId, String name, String icon, String color) {
        String normalizedName = normalizeName(name);
        categories.lockSortOrderForUser(SORT_ORDER_LOCK_CLASS, userId);
        int next = categories.findMaxSortOrder(userId).map(max -> max + 1).orElse(1);
        if (next > SORT_ORDER_MAX) {
            throw new ApiException(HttpStatus.CONFLICT, "CATEGORY_SORT_ORDER_LIMIT",
                    "No sort order is left for a new category; lower the sortOrder of an existing category");
        }
        CategoryEntity category = CategoryEntity.create(userId, normalizedName, icon, normalizeColor(color), null,
                (short) next, clock.instant());
        CategoryEntity saved = flushChecked(() -> categories.saveAndFlush(category));
        log.info("Category created: categoryId={}, userId={}", saved.getId(), userId);
        return saved;
    }

    /** Archived categories can be edited too; a rename only conflicts with other active categories. */
    @Transactional
    CategoryEntity update(UUID userId, UUID categoryId, Changes changes) {
        CategoryEntity category = find(userId, categoryId);
        if (changes.name() != null) {
            category.rename(normalizeName(changes.name()));
        }
        if (changes.icon() != null) {
            category.changeIcon(changes.icon());
        }
        if (changes.color() != null) {
            category.changeColor(normalizeColor(changes.color()));
        }
        if (changes.sortOrder() != null) {
            int sortOrder = changes.sortOrder();
            if (sortOrder < 0 || sortOrder > SORT_ORDER_MAX) {
                throw ApiException.validation("sortOrder", "must be between 0 and " + SORT_ORDER_MAX);
            }
            category.changeSortOrder((short) sortOrder);
        }
        category.touch(clock.instant());
        return flushChecked(() -> categories.saveAndFlush(category));
    }

    /** Idempotent: archiving an archived category keeps its original archive time. */
    @Transactional
    CategoryEntity archive(UUID userId, UUID categoryId) {
        CategoryEntity category = find(userId, categoryId);
        if (DefaultCategories.OTHER.equals(category.systemKey())) {
            throw new ApiException(HttpStatus.CONFLICT, "CATEGORY_PROTECTED",
                    "The 'Other' category cannot be archived");
        }
        if (!category.archived()) {
            Instant now = clock.instant();
            category.archive(now);
            category.touch(now);
            categories.saveAndFlush(category);
        }
        return category;
    }

    /** Idempotent; fails if an active category already uses the same name (case-insensitive). */
    @Transactional
    CategoryEntity unarchive(UUID userId, UUID categoryId) {
        CategoryEntity category = find(userId, categoryId);
        if (category.archived()) {
            category.unarchive();
            category.touch(clock.instant());
            flushChecked(() -> categories.saveAndFlush(category));
        }
        return category;
    }

    private CategoryEntity find(UUID userId, UUID categoryId) {
        return categories.findByIdAndUserId(categoryId, userId)
                .orElseThrow(CategoryService::notFound);
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Category not found");
    }

    /**
     * Stored without surrounding blank characters. Java's {@code strip()} misses some of them
     * (no-break spaces U+00A0/U+2007/U+202F, U+0085) and PostgreSQL's {@code btrim(name)} only
     * removes U+0020, so the rule is defined here: every Unicode White_Space character plus the
     * invisible U+200B (zero width space) and U+FEFF (BOM). The result never starts or ends with
     * U+0020, so the database check {@code char_length(btrim(name))} sees exactly this value.
     *
     * <p>Length is counted in characters (code points) as PostgreSQL does. Case-insensitive
     * uniqueness is left to the database index on {@code lower(name)} of this stored value, so
     * Java and SQL can never disagree on it.
     */
    static String normalizeName(String name) {
        String trimmed = name == null ? "" : trimBlank(name);
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < 1 || length > NAME_MAX) {
            throw ApiException.validation("name", "must be 1-" + NAME_MAX + " characters");
        }
        return trimmed;
    }

    static boolean isBlank(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                || codePoint == 0x0085 || codePoint == 0x200B || codePoint == 0xFEFF;
    }

    private static String trimBlank(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isBlank(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && isBlank(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }

    static String normalizeColor(String color) {
        return color.toUpperCase(Locale.ROOT);
    }

    private static CategoryEntity flushChecked(Supplier<CategoryEntity> write) {
        try {
            return write.get();
        } catch (DataIntegrityViolationException e) {
            Throwable cause = e.getMostSpecificCause();
            if (cause.getMessage() != null && cause.getMessage().contains(ACTIVE_NAME_CONSTRAINT)) {
                throw new ApiException(HttpStatus.CONFLICT, "CATEGORY_NAME_TAKEN",
                        "An active category with this name already exists");
            }
            throw e;
        }
    }
}
