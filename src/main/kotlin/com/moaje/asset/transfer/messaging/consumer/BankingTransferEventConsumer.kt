package com.moaje.asset.transfer.messaging.consumer

import com.moaje.asset.transfer.application.service.BankingTransferEventHandler
import com.moaje.asset.transfer.application.usecase.ApplyBankingTransferCompletedCommand
import com.moaje.asset.transfer.application.usecase.ApplyBankingTransferFailedCommand
import com.moaje.asset.transfer.application.usecase.ApplyBankingTransferReversedCommand
import com.moaje.events.banking.BankingTransferCompletedEvent
import com.moaje.events.banking.BankingTransferFailedEvent
import com.moaje.events.banking.BankingTransferReversedEvent
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

@Component
class BankingTransferEventConsumer(
    private val bankingTransferEventHandler: BankingTransferEventHandler,
) {
    /**
     * Banking 서비스가 발행한 송금 성공 Kafka 메시지를 소비합니다.
     */
    @KafkaListener(topics = ["\${moaje.asset.kafka.topics.banking-transfer-completed}"])
    fun consumeCompleted(payload: ByteArray) {
        val event = BankingTransferCompletedEvent.parseFrom(payload)
        bankingTransferEventHandler.handleTransferCompleted(event.toCommand())
    }

    /**
     * Banking 서비스가 발행한 송금 실패 Kafka 메시지를 소비합니다.
     */
    @KafkaListener(topics = ["\${moaje.asset.kafka.topics.banking-transfer-failed}"])
    fun consumeFailed(payload: ByteArray) {
        val event = BankingTransferFailedEvent.parseFrom(payload)
        bankingTransferEventHandler.handleTransferFailed(event.toCommand())
    }

    /**
     * Banking이 대사로 확인한 외부 보상 완료 이벤트를 소비합니다.
     * Completed와 다른 Topic에서 올 수 있으므로 실제 잔액 반영 순서는 Application Service가 다시 검증합니다.
     */
    @KafkaListener(topics = ["\${moaje.asset.kafka.topics.banking-transfer-reversed}"])
    fun consumeReversed(payload: ByteArray) {
        val event = BankingTransferReversedEvent.parseFrom(payload)
        bankingTransferEventHandler.handleTransferReversed(event.toCommand())
    }

    private fun BankingTransferCompletedEvent.toCommand(): ApplyBankingTransferCompletedCommand {
        return ApplyBankingTransferCompletedCommand(
            eventId = eventId,
            bankingTransferId = transferId,
            userId = userId,
            accountId = accountId,
            amount = BigDecimal.valueOf(amount.amount),
            externalTransactionId = externalTransactionId,
            occurredAt = Instant.parse(occurredAt),
        )
    }

    private fun BankingTransferFailedEvent.toCommand(): ApplyBankingTransferFailedCommand {
        return ApplyBankingTransferFailedCommand(
            eventId = eventId,
            bankingTransferId = transferId,
            userId = userId,
            accountId = accountId,
            amount = BigDecimal.valueOf(amount.amount),
            reason = reason,
            failureStatus = failureStatus.ifBlank { "FAILED" },
            occurredAt = Instant.parse(occurredAt),
        )
    }

    private fun BankingTransferReversedEvent.toCommand(): ApplyBankingTransferReversedCommand {
        return ApplyBankingTransferReversedCommand(
            eventId = eventId,
            bankingTransferId = transferId,
            userId = userId,
            accountId = accountId,
            amount = BigDecimal.valueOf(amount.amount),
            originalExternalTransactionId = originalExternalTransactionId.takeIf { it.isNotBlank() },
            externalReversalTransactionId = externalReversalTransactionId.takeIf { it.isNotBlank() },
            reason = reason.takeIf { it.isNotBlank() },
            occurredAt = Instant.parse(occurredAt),
        )
    }
}
