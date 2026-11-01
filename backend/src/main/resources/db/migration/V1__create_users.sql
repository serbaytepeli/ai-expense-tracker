-- Users (docs/database-design.md section 6). Owned by the `user` module.
-- Timestamps have no database defaults: the application sets them from the injected Clock.

CREATE TABLE users (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    -- Normalized by the application (trim + lower case with Locale.ROOT), see section 3.
    email         VARCHAR(254) NOT NULL,
    -- BCrypt hash (cost 12). Never a plain-text password.
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(60)  NOT NULL,
    currency      CHAR(3)      NOT NULL DEFAULT 'TRY',
    time_zone     VARCHAR(64)  NOT NULL DEFAULT 'Europe/Istanbul',
    locale        VARCHAR(10)  NOT NULL DEFAULT 'tr-TR',
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
);
