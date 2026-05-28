package com.moaje.asset.domain.transaction

import com.moaje.asset.common.id.TsidGeneratedValue
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(
    name = "transaction_history",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_transaction_history_idempotency_key", columnNames = ["idempotency_key"]),
        UniqueConstraint(name = "uk_transaction_history_public_transfer_id", columnNames = ["public_transfer_id"]),
        UniqueConstraint(name = "uk_transaction_history_external_transaction_id", columnNames = ["external_transaction_id"]),
    ],
    indexes = [
        Index(name = "idx_transaction_history_account_id", columnList = "account_id"),
        Index(name = "idx_transaction_history_status", columnList = "status"),
        Index(name = "idx_transaction_history_created_at", columnList = "created_at"),
        Index(name = "idx_transaction_history_external_transaction_id", columnList = "external_transaction_id"),
    ],
)
class TransactionHistory(
    @Id
    @TsidGeneratedValue
    @Column(name = "id", nullable = false)
    var id: Long? = null,

    @Column(name = "account_id", nullable = false)
    var accountId: Long,

    @Column(name = "public_transfer_id", nullable = false, length = 80)
    var publicTransferId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    var type: TransactionType,

    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    var amount: BigDecimal,

    @Column(name = "target_token", length = 255)
    var targetToken: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: TransactionStatus = TransactionStatus.PENDING,

    @Column(name = "idempotency_key", nullable = false, length = 255)
    var idempotencyKey: String,

    @Column(name = "external_transaction_id", length = 100)
    var externalTransactionId: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun markSuccess(externalTransactionId: String? = null) {
        if (!externalTransactionId.isNullOrBlank()) {
            this.externalTransactionId = externalTransactionId
        }
        status = TransactionStatus.SUCCESS
    }

    fun markFailed() {
        status = TransactionStatus.FAILED
    }
}

