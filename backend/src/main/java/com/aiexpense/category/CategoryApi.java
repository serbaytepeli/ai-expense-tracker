package com.aiexpense.category;

import java.util.UUID;

/** Public API of the category module for other modules (docs/architecture.md section 3). */
public interface CategoryApi {

    /**
     * Creates the built-in categories for a new user. Must run inside the caller's transaction
     * (registration), so a failure here also rolls back the new user.
     */
    void createDefaultCategories(UUID userId);
}
