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
    // Asset이 거래내역 Row를 구분하기 위해 발급하는 내부 PK다.
    @Id
    @TsidGeneratedValue
    @Column(name = "id", nullable = false)
    var id: Long? = null,

    // 이 거래내역이 속한 Asset 계좌의 ID다. 단건 조회 시 사용자 소유권 검증에도 사용한다.
    @Column(name = "account_id", nullable = false)
    var accountId: Long,

    // Banking에서 시작된 송금이면 Banking의 transferId를 저장한다. ATM 등 외부 거래는 null일 수 있다.
    @Column(name = "banking_transfer_id")
    var bankingTransferId: Long? = null,

    // 이 거래가 Asset 잔액에 미친 방향이다. 송금 출금, 송금 입금, 반전 입금을 구분한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "balance_change_type", length = 30)
    var balanceChangeType: BalanceChangeType? = null,

    // 사용자 화면과 외부 응답에 노출할 Asset 거래 식별자다. DB의 내부 PK를 그대로 노출하지 않는다.
    @Column(name = "public_transfer_id", nullable = false, length = 80)
    var publicTransferId: String,

    // 입금, 출금, 송금, 반전 중 사용자 관점의 거래 종류다.
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    var type: TransactionType,

    // 해당 거래의 금액이다. 입출금 방향은 부호가 아니라 type과 balanceChangeType으로 구분한다.
    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    var amount: BigDecimal,

    // 상대 계좌를 직접 노출하지 않기 위한 불투명 식별자다. 현재 생성 경로에서는 값이 없을 수 있다.
    @Column(name = "target_token", length = 255)
    var targetToken: String? = null,

    // Asset 장부에서 본 거래 처리 상태다. 계정계의 거래 상태와 완전히 같은 개념은 아니다.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: TransactionStatus = TransactionStatus.PENDING,

    // 같은 금융 효과를 두 번 저장하지 않기 위한 Asset 내부 멱등성 키다.
    @Column(name = "idempotency_key", nullable = false, length = 255)
    var idempotencyKey: String,

    // Mock Banking 등 외부 계정계가 발급한 거래 ID다. 외부 거래와 내부 거래를 연결할 때 사용한다.
    @Column(name = "external_transaction_id", length = 100)
    var externalTransactionId: String? = null,

    // 실패 placeholder처럼 사용자에게 보여주지 않을 내부 기록을 구분한다.
    @Column(name = "visible_to_user", nullable = false, columnDefinition = "boolean default true")
    var visibleToUser: Boolean = true,

    // 거래 실패 또는 내부 기록이 숨겨진 이유다. 성공 거래에서는 보통 null이다.
    @Column(name = "failure_reason", length = 255)
    var failureReason: String? = null,

    // Banking 조회나 계좌 스냅샷으로 결과를 다시 확인해야 하는 거래인지 나타낸다.
    @Column(name = "reconciliation_required", nullable = false, columnDefinition = "boolean default false")
    var reconciliationRequired: Boolean = false,

    // 거래가 외부 계정계에서 실제로 발생한 시각이다. Asset이 발견한 시각과 다를 수 있다.
    @Column(name = "external_occurred_at")
    var externalOccurredAt: LocalDateTime? = null,

    // 외부 계정계가 거래 처리를 완료한 시각이다. 완료 시각을 제공하지 않으면 null이다.
    @Column(name = "external_completed_at")
    var externalCompletedAt: LocalDateTime? = null,

    // 누락됐던 거래를 대사 또는 스냅샷 조회로 Asset에 복구한 시각이다.
    @Column(name = "recovered_at")
    var recoveredAt: LocalDateTime? = null,

    // 외부 계정계가 표현한 원래 거래 종류다. 외부 값을 보존해 변환 결과를 추적할 때 사용한다.
    @Column(name = "external_type", length = 30)
    var externalType: String? = null,

    // Work용 거래 성공 이벤트를 Outbox에 이미 기록했는지 나타내 중복 생성을 막는다.
    @Column(name = "work_event_recorded", nullable = false)
    var workEventRecorded: Boolean = false,

    // 계정계가 제공한 가맹점 이름이다. 개인 간 송금이나 ATM 거래처럼 가맹점이 없는 거래는 null일 수 있다.
    @Column(name = "merchant_name", length = 100)
    var merchantName: String? = null,

    // 월별 집계에 사용할 Asset의 최종 분류값이다. 근거가 부족한 거래는 임의 분류하지 않는다.
    @Enumerated(EnumType.STRING)
    @Column(name = "category_code", nullable = false, length = 50)
    var categoryCode: TransactionCategory = TransactionCategory.UNCATEGORIZED,

    // Asset에 거래내역 Row가 생성된 시각이다. 외부 거래 복구 시에는 실제 발생 시각으로 맞출 수 있다.
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    // 거래내역의 상태나 복구 정보가 마지막으로 변경된 시각이다.
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

