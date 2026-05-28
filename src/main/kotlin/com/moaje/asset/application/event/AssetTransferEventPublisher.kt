package com.moaje.asset.application.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.moaje.common.Money as ProtoMoney
import com.moaje.events.asset.AssetTransferRequestedEvent
import com.moaje.events.asset.TransactionSucceededEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import com.moaje.asset.repository.outbox.TransactionalOutboxRepository
import java.util.concurrent.TimeUnit

@Component
@ConfigurationProperties(prefix = "moaje.asset.kafka.topics")
class AssetKafkaTopicsProperties {
    var assetTransferRequested: String = "moaje.asset.transfer-requested"
    var bankingTransferCompleted: String = "moaje.banking.transfer-completed"
    var bankingTransferFailed: String = "moaje.banking.transfer-failed"
    var transactionSucceeded: String = "transaction_succeeded_events"
}

@Component
class AssetTransferEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, ByteArray>,
    private val topics: AssetKafkaTopicsProperties,
    private val transactionalOutboxRepository: TransactionalOutboxRepository,
    private val objectMapper: ObjectMapper,
) {
    /**
     * 아직 Kafka로 전송되지 않은 송금 요청 outbox를 주기적으로 읽어 실제 Kafka 토픽으로 발행합니다.
     */
    @Scheduled(fixedDelayString = "\${moaje.asset.kafka.outbox-publish-delay-ms:1000}")
    @Transactional
    fun publishPendingTransferRequestedEvents() {
        val outboxes = transactionalOutboxRepository
            .findTop100ByAggregateTypeAndPublishedFalseOrderByCreatedAtAsc(TRANSFER_REQUESTED)

        outboxes.forEach { outbox ->
            val payload = objectMapper.readValue(outbox.payload, AssetTransferRequestedOutboxPayload::class.java)
            val event = payload.toProtoEvent()

            kafkaTemplate
                .send(topics.assetTransferRequested, payload.transferId.toString(), event.toByteArray())
                .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            outbox.markPublished()
        }
    }

    /**
     * 아직 Kafka로 전송되지 않은 거래 성공 outbox를 읽어 Work 도메인 분석용 토픽으로 발행합니다.
     */
    @Scheduled(fixedDelayString = "\${moaje.asset.kafka.outbox-publish-delay-ms:1000}")
    @Transactional
    fun publishPendingTransactionSucceededEvents() {
        val outboxes = transactionalOutboxRepository
            .findTop100ByAggregateTypeAndPublishedFalseOrderByCreatedAtAsc(TRANSACTION_SUCCEEDED)

        outboxes.forEach { outbox ->
            val payload = objectMapper.readValue(outbox.payload, TransactionSucceededOutboxPayload::class.java)
            val event = payload.toProtoEvent()

            kafkaTemplate
                .send(topics.transactionSucceeded, payload.transactionId.toString(), event.toByteArray())
                .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            outbox.markPublished()
        }
    }

    /**
     * outbox에 저장된 JSON payload를 Banking 서비스가 소비할 protobuf 이벤트로 변환합니다.
     */
    private fun AssetTransferRequestedOutboxPayload.toProtoEvent(): AssetTransferRequestedEvent {
        return AssetTransferRequestedEvent.newBuilder()
            .setEventId(eventId)
            .setTransferId(transferId)
            .setAssetTransactionId(assetTransactionId)
            .setUserId(userId)
            .setCi(ci)
            .setUserName(userName)
            .setPhoneNumber(phoneNumber)
            .setWithdrawalAccountId(withdrawalAccountId)
            .setDepositBankCode(depositBankCode)
            .setDepositAccountNumber(depositAccountNumber)
            .setAmount(
                ProtoMoney.newBuilder()
                    .setAmount(amount.toLong())
                    .setCurrency(currency)
                    .build(),
            )
            .setOccurredAt(occurredAt.toString())
            .build()
    }

    /**
     * 거래 성공 outbox JSON payload를 Work 도메인이 소비할 protobuf 이벤트로 변환합니다.
     */
    private fun TransactionSucceededOutboxPayload.toProtoEvent(): TransactionSucceededEvent {
        return TransactionSucceededEvent.newBuilder()
            .setEventId(eventId)
            .setTransactionId(transactionId)
            .setPublicTransactionId(publicTransactionId)
            .setUserId(userId)
            .setAccountId(accountId)
            .setAccountToken(accountToken)
            .setExternalTransactionId(externalTransactionId.orEmpty())
            .setTransactionType(transactionType)
            .setAmount(amount.toProtoMoney(currency))
            .setTargetToken(targetToken.orEmpty())
            .setBalanceAfterTransaction(balanceAfterTransaction.toProtoMoney(currency))
            .setSucceededAt(succeededAt.toString())
            .setOccurredAt(occurredAt.toString())
            .build()
    }

    /**
     * Asset 내부 금액 값을 protobuf Money 메시지로 변환합니다.
     */
    private fun java.math.BigDecimal.toProtoMoney(currency: String): ProtoMoney {
        return ProtoMoney.newBuilder()
            .setAmount(toLong())
            .setCurrency(currency)
            .build()
    }

    companion object {
        const val TRANSFER_REQUESTED = "KFTC_TRANSFER_REQUEST"
        const val TRANSACTION_SUCCEEDED = "TRANSACTION_SUCCEEDED"
        private const val KAFKA_SEND_TIMEOUT_SECONDS = 5L
    }
}
