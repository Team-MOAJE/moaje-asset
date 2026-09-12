package com.moaje.asset.transfer.domain

import com.moaje.asset.common.id.TsidGeneratedValue
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
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
        UniqueConstraint(
            name = "uk_transaction_history_transfer_account_balance_change",
            columnNames = ["banking_transfer_id", "account_id", "balance_change_type"],
        ),
    ],
    indexes = [
        Index(name = "idx_transaction_history_account_id", columnList = "account_id"),
        Index(name = "idx_transaction_history_status", columnList = "status"),
        Index(name = "idx_transaction_history_created_at", columnList = "created_at"),
        Index(name = "idx_transaction_history_external_transaction_id", columnList = "external_transaction_id"),
        Index(name = "idx_transaction_history_banking_transfer_id", columnList = "banking_transfer_id"),
    ],
)
class TransactionHistory(
    @Id
    @TsidGeneratedValue
    @Column(name = "id", nullable = false)
    var id: Long? = null,

    @Column(name = "account_id", nullable = false)
    var accountId: Long,

    @Column(name = "banking_transfer_id")
    var bankingTransferId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "balance_change_type", length = 30)
    var balanceChangeType: BalanceChangeType? = null,

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

    @Column(name = "visible_to_user", nullable = false, columnDefinition = "boolean default true")
    var visibleToUser: Boolean = true,

    @Column(name = "failure_reason", length = 255)
    var failureReason: String? = null,

    @Column(name = "reconciliation_required", nullable = false, columnDefinition = "boolean default false")
    var reconciliationRequired: Boolean = false,

    @Column(name = "external_occurred_at")
    var externalOccurredAt: LocalDateTime? = null,

    @Column(name = "external_completed_at")
    var externalCompletedAt: LocalDateTime? = null,

    @Column(name = "recovered_at")
    var recoveredAt: LocalDateTime? = null,

    @Column(name = "external_type", length = 30)
    var externalType: String? = null,

    @Column(name = "work_event_recorded", nullable = false)
    var workEventRecorded: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    /**
     * 스냅샷으로 이미 반영한 거래에 늦게 도착한 Banking 거래 번호만 연결한다.
     * 같은 영수증에 내부 접수 번호를 덧붙이는 작업이므로 잔액과 Work 이벤트는 다시 만들지 않는다.
     */
    fun linkBankingEffect(transferId: Long, effect: BalanceChangeType) {
        require(status == TransactionStatus.SUCCESS) { "성공한 외부 거래만 연결할 수 있습니다." }
        require(bankingTransferId == null || bankingTransferId == transferId) { "외부 거래의 Banking ID가 다릅니다." }
        require(balanceChangeType == null || balanceChangeType == effect) { "외부 거래의 금융 효과가 다릅니다." }
        require(externalType == null || externalType == effect.name) { "외부 거래의 입출금 방향이 다릅니다." }
        bankingTransferId = transferId
        balanceChangeType = effect
        reconciliationRequired = false
    }
    fun markSuccess(externalTransactionId: String? = null) {
        if (!externalTransactionId.isNullOrBlank()) {
            this.externalTransactionId = externalTransactionId
        }
        status = TransactionStatus.SUCCESS
        visibleToUser = true
        reconciliationRequired = false
    }

    fun markFailed(reason: String? = null) {
        status = TransactionStatus.FAILED
        failureReason = reason
        visibleToUser = false
        reconciliationRequired = false
    }

    fun markCanceled(reason: String? = null) {
        status = TransactionStatus.CANCELED
        failureReason = reason
        visibleToUser = false
        reconciliationRequired = false
    }

    fun markUnknown(reason: String? = null) {
        status = TransactionStatus.UNKNOWN
        failureReason = reason
        visibleToUser = false
        reconciliationRequired = true
    }

    /**
     * 대사에서 확정된 금융 효과를 기존 UNKNOWN/PENDING placeholder에 채운다.
     * 새 Row를 만들지 않고 원 요청 이력을 승격해야 동일 transfer의 감사 흐름과 DB 멱등 키를 보존할 수 있다.
     */
    fun confirmBalanceEffect(
        confirmedBalanceChangeType: BalanceChangeType,
        externalTransactionId: String?,
    ) {
        require(status == TransactionStatus.UNKNOWN || status == TransactionStatus.PENDING) {
            "확정되지 않은 거래내역만 대사 결과로 승격할 수 있습니다. status=$status"
        }
        require(balanceChangeType == null || balanceChangeType == confirmedBalanceChangeType) {
            "기존 잔액 효과와 대사 결과가 일치하지 않습니다."
        }
        balanceChangeType = confirmedBalanceChangeType
        markSuccess(externalTransactionId)
    }

    fun markReconciliationSuperseded(reason: String) {
        reconciliationRequired = false
        visibleToUser = false
        failureReason = reason.take(255)
    }
}


