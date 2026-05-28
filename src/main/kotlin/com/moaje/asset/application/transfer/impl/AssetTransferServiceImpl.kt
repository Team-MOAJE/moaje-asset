package com.moaje.asset.application.transfer.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.moaje.asset.application.event.AssetTransferEventPublisher
import com.moaje.asset.application.event.AssetTransferRequestedOutboxPayload
import com.moaje.asset.application.event.TransactionSucceededOutboxPayload
import com.moaje.asset.application.transfer.AssetTransferService
import com.moaje.asset.application.transfer.ApplyExternalTransactionCommand
import com.moaje.asset.application.transfer.CompleteTransferCommand
import com.moaje.asset.application.transfer.FailTransferCommand
import com.moaje.asset.application.transfer.ReserveTransferCommand
import com.moaje.asset.application.transfer.ReserveTransferResult
import com.moaje.asset.common.id.IdGenerator
import com.moaje.asset.domain.account.Account
import com.moaje.asset.domain.outbox.TransactionalOutbox
import com.moaje.asset.domain.transaction.TransactionHistory
import com.moaje.asset.domain.transaction.TransactionStatus
import com.moaje.asset.domain.transaction.TransactionType
import com.moaje.asset.repository.account.AccountRepository
import com.moaje.asset.repository.outbox.TransactionalOutboxRepository
import com.moaje.asset.repository.transaction.TransactionHistoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class AssetTransferServiceImpl(
    private val accountRepository: AccountRepository,
    private val transactionHistoryRepository: TransactionHistoryRepository,
    private val transactionalOutboxRepository: TransactionalOutboxRepository,
    private val idGenerator: IdGenerator,
    private val objectMapper: ObjectMapper,
) : AssetTransferService {
    /**
     * Banking 서비스의 송금 요청을 가승인하고, 실제 금융망 처리를 위한 Kafka outbox 이벤트를 저장합니다.
     */
    @Transactional
    override fun reserve(command: ReserveTransferCommand): ReserveTransferResult {
        val existingTransaction = transactionHistoryRepository.findByIdempotencyKey(command.idempotencyKey)
        if (existingTransaction != null) {
            return existingTransaction.toReserveTransferResult()
        }

        val account = accountRepository.findByIdForUpdate(command.accountId)
            ?: throw IllegalArgumentException("계좌를 찾을 수 없습니다.")

        require(account.userId == command.userId) { "사용자의 계좌가 아닙니다." }
        account.reserve(command.amount)

        val transaction = transactionHistoryRepository.save(
            TransactionHistory(
                accountId = account.id ?: throw IllegalStateException("계좌 ID가 없습니다."),
                publicTransferId = idGenerator.nextBankingTransferPublicId(),
                type = TransactionType.TRANSFER,
                amount = command.amount,
                targetToken = command.targetToken,
                status = TransactionStatus.PENDING,
                idempotencyKey = command.idempotencyKey,
            ),
        )

        transactionalOutboxRepository.save(
            TransactionalOutbox(
                aggregateType = AssetTransferEventPublisher.TRANSFER_REQUESTED,
                aggregateId = transaction.id ?: throw IllegalStateException("거래 ID가 없습니다."),
                payload = objectMapper.writeValueAsString(command.toTransferRequestedPayload(transaction)),
            ),
        )

        return transaction.toReserveTransferResult()
    }

    /**
     * Banking 서비스가 발행한 성공 이벤트를 반영하여 PENDING 거래를 SUCCESS 상태로 확정합니다.
     */
    @Transactional
    override fun complete(command: CompleteTransferCommand) {
        val transaction = transactionHistoryRepository.findByIdForUpdate(command.transferId)
            ?: throw IllegalArgumentException("거래 내역을 찾을 수 없습니다.")

        if (transaction.status == TransactionStatus.SUCCESS) {
            return
        }

        require(transaction.status == TransactionStatus.PENDING) { "대기 중인 거래만 성공 처리할 수 있습니다." }
        transaction.markSuccess(command.externalTransactionId)

        val account = accountRepository.findById(transaction.accountId)
            .orElseThrow { IllegalArgumentException("계좌를 찾을 수 없습니다.") }
        saveTransactionSucceededOutbox(transaction, account)
    }

    /**
     * Banking 서비스가 발행한 실패 이벤트를 반영하여 예약 차감액을 복원하고 거래를 FAILED 상태로 변경합니다.
     */
    @Transactional
    override fun fail(command: FailTransferCommand) {
        val transaction = transactionHistoryRepository.findByIdForUpdate(command.transferId)
            ?: throw IllegalArgumentException("거래 내역을 찾을 수 없습니다.")

        if (transaction.status == TransactionStatus.FAILED) {
            return
        }

        require(transaction.status == TransactionStatus.PENDING) { "대기 중인 거래만 실패 처리할 수 있습니다." }

        val account = accountRepository.findByIdForUpdate(transaction.accountId)
            ?: throw IllegalArgumentException("계좌를 찾을 수 없습니다.")

        account.restore(transaction.amount)
        transaction.markFailed()
    }

    /**
     * 외부 금융망에서 새로 발견한 입출금 내역을 외부 거래 고유 ID 기준으로 한 번만 원장에 반영합니다.
     */
    @Transactional
    override fun applyExternalTransaction(command: ApplyExternalTransactionCommand) {
        if (transactionHistoryRepository.existsByExternalTransactionId(command.externalTransactionId)) {
            return
        }

        val account = accountRepository.findByIdForUpdate(command.accountId)
            ?: throw IllegalArgumentException("계좌를 찾을 수 없습니다.")

        require(account.userId == command.userId) { "사용자의 계좌가 아닙니다." }
        require(account.accountToken == command.accountToken) { "계좌 토큰이 일치하지 않습니다." }

        val transactionType = command.type.toAssetTransactionType()
        when (transactionType) {
            TransactionType.DEPOSIT -> account.deposit(command.amount)
            TransactionType.WITHDRAW,
            TransactionType.TRANSFER,
            -> account.withdraw(command.amount)
        }

        val transaction = transactionHistoryRepository.save(
            TransactionHistory(
                accountId = account.id ?: throw IllegalStateException("계좌 ID가 없습니다."),
                publicTransferId = idGenerator.nextBankingTransferPublicId(),
                type = transactionType,
                amount = command.amount,
                targetToken = command.targetToken,
                status = TransactionStatus.SUCCESS,
                idempotencyKey = "external:${command.externalTransactionId}",
                externalTransactionId = command.externalTransactionId,
            ),
        )
        saveTransactionSucceededOutbox(transaction, account)
    }

    /**
     * 가승인 커맨드와 거래 엔티티를 Kafka 발행용 outbox payload로 변환합니다.
     */
    private fun ReserveTransferCommand.toTransferRequestedPayload(
        transaction: TransactionHistory,
    ): AssetTransferRequestedOutboxPayload {
        val transactionId = transaction.id ?: throw IllegalStateException("거래 ID가 없습니다.")

        return AssetTransferRequestedOutboxPayload(
            eventId = UUID.randomUUID().toString(),
            transferId = transactionId,
            assetTransactionId = transactionId,
            userId = userId,
            ci = ci,
            userName = userName,
            phoneNumber = phoneNumber,
            withdrawalAccountId = withdrawalAccountId,
            depositBankCode = depositBankCode,
            depositAccountNumber = depositAccountNumber,
            amount = amount,
            occurredAt = Instant.now(),
        )
    }

    /**
     * 거래 엔티티의 내부 식별자와 외부 노출 식별자를 Banking 응답용 결과 객체로 변환합니다.
     */
    private fun TransactionHistory.toReserveTransferResult(): ReserveTransferResult {
        val transactionId = id ?: throw IllegalStateException("거래 ID가 없습니다.")

        return ReserveTransferResult(
            transferId = transactionId,
            publicTransferId = publicTransferId,
            assetTransactionId = transactionId,
        )
    }

    /**
     * 목업 금융망의 거래 유형 문자열을 Asset 원장 거래 유형으로 변환합니다.
     */
    /**
     * SUCCESS로 확정된 거래를 Work 도메인이 소비할 수 있도록 outbox에 기록합니다.
     */
    private fun saveTransactionSucceededOutbox(
        transaction: TransactionHistory,
        account: Account,
    ) {
        val transactionId = transaction.id ?: throw IllegalStateException("거래 ID가 없습니다.")
        val accountId = account.id ?: throw IllegalStateException("계좌 ID가 없습니다.")
        val now = Instant.now()

        transactionalOutboxRepository.save(
            TransactionalOutbox(
                aggregateType = AssetTransferEventPublisher.TRANSACTION_SUCCEEDED,
                aggregateId = transactionId,
                payload = objectMapper.writeValueAsString(
                    TransactionSucceededOutboxPayload(
                        eventId = UUID.randomUUID().toString(),
                        transactionId = transactionId,
                        publicTransactionId = transaction.publicTransferId,
                        userId = account.userId,
                        accountId = accountId,
                        accountToken = account.accountToken,
                        externalTransactionId = transaction.externalTransactionId,
                        transactionType = transaction.type.name,
                        amount = transaction.amount,
                        targetToken = transaction.targetToken,
                        balanceAfterTransaction = account.balance,
                        succeededAt = now,
                        occurredAt = now,
                    ),
                ),
            ),
        )
    }

    private fun String.toAssetTransactionType(): TransactionType {
        return when (uppercase()) {
            "DEPOSIT", "CREDIT", "TRANSFER_IN" -> TransactionType.DEPOSIT
            "WITHDRAW", "WITHDRAWAL", "DEBIT", "TRANSFER_OUT" -> TransactionType.WITHDRAW
            "TRANSFER" -> TransactionType.TRANSFER
            else -> throw IllegalArgumentException("지원하지 않는 외부 거래 유형입니다: $this")
        }
    }
}
