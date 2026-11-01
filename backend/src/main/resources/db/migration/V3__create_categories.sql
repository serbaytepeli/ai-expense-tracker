-- Categories (docs/database-design.md section 6). Owned by the `category` module.
-- Categories are archived, never deleted. Built-in categories are copied per user at
-- registration by the application (no seed data here).

CREATE TABLE categories (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL,
    name        VARCHAR(30) NOT NULL,
    icon        VARCHAR(40) NOT NULL,
    color       CHAR(7)     NOT NULL,
    -- Key of a built-in category (GROCERIES, OTHER, ...); NULL for user-created categories.
    system_key  VARCHAR(30) NULL,
    sort_order  SMALLINT    NOT NULL DEFAULT 0,
    archived_at TIMESTAMPTZ NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT fk_categories_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- Target of the composite foreign key from expenses (V4): an expense's category must
    -- belong to the same user.
    CONSTRAINT uk_categories_id_user UNIQUE (id, user_id),
    CONSTRAINT ck_categories_name_length CHECK (char_length(btrim(name)) BETWEEN 1 AND 30),
    CONSTRAINT ck_categories_color CHECK (color ~ '^#[0-9A-Fa-f]{6}$'),
    -- "Other" is the fallback category and can never be archived.
    CONSTRAINT ck_categories_other_not_archived CHECK (NOT (system_key = 'OTHER' AND archived_at IS NOT NULL))
);

-- No two active categories with the same name (case-insensitive) for one user.
CREATE UNIQUE INDEX uk_categories_user_active_name ON categories (user_id, lower(name)) WHERE archived_at IS NULL;

-- Each built-in category exists at most once per user.
CREATE UNIQUE INDEX uk_categories_user_system_key ON categories (user_id, system_key) WHERE system_key IS NOT NULL;
