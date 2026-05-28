package com.moaje.asset.infra.kafka

import com.moaje.asset.application.event.BankingTransferEventHandler
import com.moaje.events.banking.BankingTransferCompletedEvent
import com.moaje.events.banking.BankingTransferFailedEvent
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

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
        bankingTransferEventHandler.handleTransferCompleted(
            transferId = event.transferId,
            externalTransactionId = event.externalTransactionId,
        )
    }

    /**
     * Banking 서비스가 발행한 송금 실패 Kafka 메시지를 소비합니다.
     */
    @KafkaListener(topics = ["\${moaje.asset.kafka.topics.banking-transfer-failed}"])
    fun consumeFailed(payload: ByteArray) {
        val event = BankingTransferFailedEvent.parseFrom(payload)
        bankingTransferEventHandler.handleTransferFailed(
            transferId = event.transferId,
            reason = event.reason,
        )
    }
}
