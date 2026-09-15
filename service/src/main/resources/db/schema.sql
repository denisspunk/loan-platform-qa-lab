-- Loans and the payments already applied to them.
-- Runs on every start, so every statement must be safe to repeat.

CREATE TABLE IF NOT EXISTS loans (
    id             text        PRIMARY KEY,
    device_id      text        NOT NULL,
    price          bigint      NOT NULL CHECK (price > 0),
    daily_rate     bigint      NOT NULL CHECK (daily_rate > 0),
    paid           bigint      NOT NULL DEFAULT 0 CHECK (paid >= 0),
    credit         bigint      NOT NULL DEFAULT 0 CHECK (credit >= 0),
    status         text        NOT NULL CHECK (status IN ('ACTIVE', 'PAID_OFF')),
    device_state   text        NOT NULL CHECK (device_state IN ('LOCKED', 'UNLOCKED', 'RELEASED')),
    unlocked_until timestamptz,
    -- a paid-off loan always has a released phone, and only a paid-off loan does
    CONSTRAINT paid_off_means_released CHECK ((status = 'PAID_OFF') = (device_state = 'RELEASED'))
);

-- added after the first release: databases created earlier get the column with the migration time
ALTER TABLE loans ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now();

CREATE TABLE IF NOT EXISTS processed_payments (
    payment_id   text        PRIMARY KEY,
    loan_id      text        NOT NULL REFERENCES loans (id),
    amount       bigint      NOT NULL,
    result       text        NOT NULL,
    processed_at timestamptz NOT NULL
);
