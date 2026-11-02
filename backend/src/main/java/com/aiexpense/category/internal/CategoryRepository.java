package com.aiexpense.category.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CategoryRepository extends JpaRepository<CategoryEntity, UUID> {

    /** Every lookup is scoped to the owner: another user's category is simply not found. */
    Optional<CategoryEntity> findByIdAndUserId(UUID id, UUID userId);

    @Query("""
            select c from CategoryEntity c
            where c.userId = :userId and (:includeArchived = true or c.archivedAt is null)
            order by c.sortOrder, c.name, c.id""")
    List<CategoryEntity> findForUser(UUID userId, boolean includeArchived);

    /** Highest sort order of the user's categories, archived ones included. */
    @Query("select max(c.sortOrder) from CategoryEntity c where c.userId = :userId")
    Optional<Short> findMaxSortOrder(UUID userId);
}
