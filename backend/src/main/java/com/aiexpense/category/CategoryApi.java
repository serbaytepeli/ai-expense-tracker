package com.aiexpense.category;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public API of the category module for other modules (docs/architecture.md section 3). */
public interface CategoryApi {

    /** What other modules need to know about a category. */
    record CategorySummary(UUID id, String name, String icon, String color, boolean archived) {
    }

    /**
     * Creates the built-in categories for a new user. Must run inside the caller's transaction
     * (registration), so a failure here also rolls back the new user.
     */
    void createDefaultCategories(UUID userId);

    /** The user's category, archived or not; empty if it does not exist or belongs to someone else. */
    Optional<CategorySummary> findForUser(UUID userId, UUID categoryId);

    /** All categories of the user, archived ones included. */
    List<CategorySummary> listForUser(UUID userId);
}
