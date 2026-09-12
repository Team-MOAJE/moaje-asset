create table account (
    id bigint not null,
    user_id varchar(100) not null,
    balance decimal(18, 4) not null,
    status varchar(20) not null,
    sync_status varchar(20) default 'SYNCED' not null,
    last_synced_at timestamp(6),
    last_sync_failed_at timestamp(6),
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    primary key (id)
);

create index idx_account_user_id on account (user_id);

create table daily_cashflow_snapshot (
    user_id varchar(255) not null,
    calculated_limit decimal(18, 4) not null,
    study_buffer decimal(18, 4) not null,
    snapshot_date date not null,
    updated_at timestamp(6) not null,
    primary key (user_id)
);

create table processed_event (
    event_id varchar(64) not null,
    event_type varchar(120) not null,
    transfer_id bigint,
    account_id bigint,
    processed_at timestamp(6) not null,
    primary key (event_id)
);

create index idx_processed_event_transfer_id on processed_event (transfer_id);
create index idx_processed_event_account_id on processed_event (account_id);
create index idx_processed_event_processed_at on processed_event (processed_at);

create table transactional_outbox (
    id bigint not null,
    aggregate_type varchar(50) not null,
    aggregate_id bigint not null,
    payload clob not null,
    is_published boolean not null,
    created_at timestamp(6) not null,
    primary key (id)
);

create index idx_transactional_outbox_published on transactional_outbox (is_published);
create index idx_transactional_outbox_created_at on transactional_outbox (created_at);

create table transaction_history (
    id bigint not null,
    account_id bigint not null,
    banking_transfer_id bigint,
    balance_change_type varchar(30),
    public_transfer_id varchar(80) not null,
    type varchar(20) not null,
    amount decimal(18, 4) not null,
    target_token varchar(255),
    status varchar(20) not null,
    idempotency_key varchar(255) not null,
    external_transaction_id varchar(100),
    visible_to_user boolean default true not null,
    failure_reason varchar(255),
    reconciliation_required boolean default false not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    primary key (id),
    constraint uk_transaction_history_idempotency_key unique (idempotency_key),
    constraint uk_transaction_history_public_transfer_id unique (public_transfer_id),
    constraint uk_transaction_history_external_transaction_id unique (external_transaction_id),
    constraint uk_transaction_history_transfer_account_balance_change
        unique (banking_transfer_id, account_id, balance_change_type)
);

create index idx_transaction_history_account_id on transaction_history (account_id);
create index idx_transaction_history_status on transaction_history (status);
create index idx_transaction_history_created_at on transaction_history (created_at);
create index idx_transaction_history_external_transaction_id on transaction_history (external_transaction_id);
create index idx_transaction_history_banking_transfer_id on transaction_history (banking_transfer_id);
