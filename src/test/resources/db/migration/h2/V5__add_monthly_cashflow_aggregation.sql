CREATE TABLE monthly_cashflow_aggregate (
    id BIGINT NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    aggregate_year INT NOT NULL,
    aggregate_month INT NOT NULL,
    income_amount DECIMAL(18, 4) NOT NULL,
    expense_amount DECIMAL(18, 4) NOT NULL,
    transfer_amount DECIMAL(18, 4) NOT NULL,
    revision BIGINT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    calculated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_monthly_cashflow_user_period UNIQUE (user_id, aggregate_year, aggregate_month)
);

CREATE TABLE monthly_category_cashflow (
    id BIGINT NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    aggregate_year INT NOT NULL,
    aggregate_month INT NOT NULL,
    category_code VARCHAR(50) NOT NULL,
    amount DECIMAL(18, 4) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_monthly_category_user_period_category
        UNIQUE (user_id, aggregate_year, aggregate_month, category_code)
);

ALTER TABLE transactional_outbox ADD COLUMN deduplication_key VARCHAR(180);
ALTER TABLE transactional_outbox
    ADD CONSTRAINT uk_transactional_outbox_deduplication_key UNIQUE (deduplication_key);
