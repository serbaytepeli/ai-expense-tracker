package com.aiexpense.user.internal;

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

/** Row of the {@code users} table (migration V1). */
@Entity
@Table(name = "users")
class UserEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 60)
    private String displayName;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(nullable = false, length = 10)
    private String locale;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew;

    protected UserEntity() {
    }

    static UserEntity create(UUID id, String email, String passwordHash, String displayName, String timeZone,
            Instant now) {
        UserEntity user = new UserEntity();
        user.id = id;
        user.email = email;
        user.passwordHash = passwordHash;
        user.displayName = displayName;
        user.currency = "TRY";
        user.timeZone = timeZone;
        user.locale = "tr-TR";
        user.createdAt = now;
        user.updatedAt = now;
        user.isNew = true;
        return user;
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

    String email() {
        return email;
    }

    String passwordHash() {
        return passwordHash;
    }

    String displayName() {
        return displayName;
    }

    String currency() {
        return currency;
    }

    String timeZone() {
        return timeZone;
    }

    String locale() {
        return locale;
    }

    @Override
    public String toString() {
        return "UserEntity[id=" + id + "]";
    }
}
