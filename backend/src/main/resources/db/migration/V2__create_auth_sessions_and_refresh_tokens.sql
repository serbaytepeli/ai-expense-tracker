-- Sessions and refresh tokens (docs/database-design.md section 6, architecture.md section 3.6).
-- Owned by the `auth` module.

-- One login = one session (token family). The access token carries the session id (`sid`).
CREATE TABLE auth_sessions (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id           UUID        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    last_refreshed_at TIMESTAMPTZ NULL,
    revoked_at        TIMESTAMPTZ NULL,
    revoke_reason     VARCHAR(30) NULL,
    CONSTRAINT pk_auth_sessions PRIMARY KEY (id),
    CONSTRAINT fk_auth_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_auth_sessions_revoke_reason
        CHECK (revoke_reason IN ('LOGOUT', 'TOKEN_REUSE', 'PASSWORD_CHANGE', 'ACCOUNT_DELETED', 'EXPIRED'))
);

CREATE INDEX ix_auth_sessions_user_active ON auth_sessions (user_id) WHERE revoked_at IS NULL;

-- Only the SHA-256 hash (hex) of each 256-bit random refresh token is stored.
CREATE TABLE refresh_tokens (
    id         UUID        NOT NULL DEFAULT gen_random_uuid(),
    session_id UUID        NOT NULL,
    token_hash CHAR(64)    NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    -- Set on rotation; a used token is never valid again.
    used_at    TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT fk_refresh_tokens_session FOREIGN KEY (session_id) REFERENCES auth_sessions (id) ON DELETE CASCADE,
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_refresh_tokens_session ON refresh_tokens (session_id);
