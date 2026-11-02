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

    /**
     * Serializes category creation per user until the transaction ends, so two concurrent creates
     * cannot read the same max(sortOrder). Transaction-scoped PostgreSQL advisory lock: no table
     * lock, released automatically on commit or rollback. Users whose IDs hash alike only wait
     * for each other briefly.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:lockClass, hashtext(CAST(:userId AS text)))) l",
            nativeQuery = true)
    int lockSortOrderForUser(int lockClass, UUID userId);

    /** Highest sort order of the user's categories, archived ones included. */
    @Query("select max(c.sortOrder) from CategoryEntity c where c.userId = :userId")
    Optional<Short> findMaxSortOrder(UUID userId);
}
