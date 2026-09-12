package com.moaje.asset.account.domain

enum class AccountSyncStatus {
    SYNCED,
    STALE,
    SYNC_REQUIRED,
    RECONCILING,
}

