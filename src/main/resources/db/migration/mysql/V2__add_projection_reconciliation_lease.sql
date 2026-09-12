alter table account
    add column sync_attempt_count integer not null default 0,
    add column sync_retry_exhausted boolean not null default false,
    add column next_sync_at datetime(6),
    add column sync_locked_at datetime(6),
    add column sync_locked_until datetime(6),
    add column sync_lock_owner varchar(120),
    add column last_sync_failure_reason varchar(500);

create index idx_account_projection_reconciliation
    on account (sync_status, sync_retry_exhausted, next_sync_at, sync_locked_until);
