-- Expenses (docs/database-design.md, expenses). Hard delete; no seed data.
-- "Not in the future" depends on the user's time zone and is checked in the service layer
-- with the injected Clock, never as a database constraint (database-design.md section 1).

CREATE TABLE expenses (
    id             UUID          NOT NULL DEFAULT gen_random_uuid(),
    user_id        UUID          NOT NULL,
    category_id    UUID          NOT NULL,
    amount         NUMERIC(14,2) NOT NULL,
    expense_date   DATE          NOT NULL,
    description    VARCHAR(140)  NULL,
    note           VARCHAR(500)  NULL,
    payment_method VARCHAR(10)   NULL,
    created_at     TIMESTAMPTZ   NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_expenses PRIMARY KEY (id),
    CONSTRAINT fk_expenses_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- The category must belong to the same user, enforced by the database.
    CONSTRAINT fk_expenses_category FOREIGN KEY (category_id, user_id) REFERENCES categories (id, user_id),
    CONSTRAINT ck_expenses_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_expenses_payment_method CHECK (payment_method IN ('CASH', 'CARD', 'OTHER'))
);

CREATE INDEX ix_expenses_user_date ON expenses (user_id, expense_date DESC, created_at DESC);
CREATE INDEX ix_expenses_user_category_date ON expenses (user_id, category_id, expense_date);
