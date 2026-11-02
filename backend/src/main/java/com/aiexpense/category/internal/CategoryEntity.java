package com.aiexpense.category.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/** Row of the {@code categories} table (migration V3). */
@Entity
@Table(name = "categories")
class CategoryEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 30)
    private String name;

    @Column(nullable = false, length = 40)
    private String icon;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 7)
    private String color;

    @Column(name = "system_key", length = 30, updatable = false)
    private String systemKey;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew;

    protected CategoryEntity() {
    }

    static CategoryEntity create(UUID userId, String name, String icon, String color, String systemKey,
            short sortOrder, Instant now) {
        CategoryEntity category = new CategoryEntity();
        category.id = UUID.randomUUID();
        category.userId = userId;
        category.name = name;
        category.icon = icon;
        category.color = color;
        category.systemKey = systemKey;
        category.sortOrder = sortOrder;
        category.createdAt = now;
        category.updatedAt = now;
        category.isNew = true;
        return category;
    }

    @Override
    public UUID getId() {
        return id;
    }

    /** Assigned IDs: tells Spring Data to INSERT (persist) instead of merging. */
    @Override
    public boolean isNew() {
        return isNew;
    }

    String name() {
        return name;
    }

    String icon() {
        return icon;
    }

    String color() {
        return color;
    }

    String systemKey() {
        return systemKey;
    }

    short sortOrder() {
        return sortOrder;
    }

    boolean archived() {
        return archivedAt != null;
    }

    void rename(String newName) {
        this.name = newName;
    }

    void changeIcon(String newIcon) {
        this.icon = newIcon;
    }

    void changeColor(String newColor) {
        this.color = newColor;
    }

    void changeSortOrder(short newSortOrder) {
        this.sortOrder = newSortOrder;
    }

    void archive(Instant now) {
        this.archivedAt = now;
    }

    void unarchive() {
        this.archivedAt = null;
    }

    void touch(Instant now) {
        this.updatedAt = now;
    }

    @Override
    public String toString() {
        return "CategoryEntity[id=" + id + "]";
    }
}
