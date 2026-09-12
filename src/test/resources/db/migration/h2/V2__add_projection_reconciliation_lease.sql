alter table account add column sync_attempt_count integer not null default 0;
alter table account add column sync_retry_exhausted boolean not null default false;
alter table account add column next_sync_at timestamp(6);
alter table account add column sync_locked_at timestamp(6);
alter table account add column sync_locked_until timestamp(6);
alter table account add column sync_lock_owner varchar(120);
alter table account add column last_sync_failure_reason varchar(500);

create index idx_account_projection_reconciliation
    on account (sync_status, sync_retry_exhausted, next_sync_at, sync_locked_until);
