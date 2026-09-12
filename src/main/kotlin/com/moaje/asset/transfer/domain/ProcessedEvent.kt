package com.moaje.asset.transfer.domain

import com.moaje.asset.common.id.TsidGeneratedValue
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime

@Entity
@Table(
    name = "processed_event",
    indexes = [
        Index(name = "idx_processed_event_transfer_id", columnList = "transfer_id"),
        Index(name = "idx_processed_event_account_id", columnList = "account_id"),
        Index(name = "idx_processed_event_processed_at", columnList = "processed_at"),
    ],
)
class ProcessedEvent(
    @Id
    @Column(name = "event_id", nullable = false, length = 64)
    var eventId: String,

    @Column(name = "event_type", nullable = false, length = 120)
    var eventType: String,

    @Column(name = "transfer_id")
    var transferId: Long? = null,

    @Column(name = "account_id")
    var accountId: Long? = null,

    @CreationTimestamp
    @Column(name = "processed_at", nullable = false)
    var processedAt: LocalDateTime = LocalDateTime.now(),
)
