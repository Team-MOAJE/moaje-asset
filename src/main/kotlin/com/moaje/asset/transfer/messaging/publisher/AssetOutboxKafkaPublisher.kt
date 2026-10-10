package com.moaje.asset.transfer.messaging.publisher

import com.fasterxml.jackson.databind.ObjectMapper
import com.moaje.asset.outbox.persistence.TransactionalOutboxRepository
import com.moaje.asset.cashflow.application.model.CategoryCashflowOutboxPayload
import com.moaje.asset.cashflow.application.service.MonthlyCashflowServiceImpl
import com.moaje.asset.cashflow.application.model.MonthlyCashflowOutboxPayload
import com.moaje.events.asset.CategoryCashflowAggregatedEvent
import com.moaje.events.asset.CategoryCashflowAmount
import com.moaje.events.asset.MonthlyCashflowAggregatedEvent
import com.moaje.common.Money as ProtoMoney
import com.moaje.events.asset.TransactionSucceededEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.TimeUnit

@Component
@ConfigurationProperties(prefix = "moaje.asset.kafka.topics")
class AssetKafkaTopicsProperties {
    var bankingTransferCompleted: String = "moaje.banking.transfer-completed"
    var bankingTransferFailed: String = "moaje.banking.transfer-failed"
    var transactionSucceeded: String = "transaction_succeeded_events"
    var monthlyCashflowAggregated: String = "moaje.asset.monthly-cashflow-aggregated"
    var categoryCashflowAggregated: String = "moaje.asset.category-cashflow-aggregated"
}

@Component
class AssetOutboxKafkaPublisher(
    private val kafkaTemplate: KafkaTemplate<String, ByteArray>,
    private val topics: AssetKafkaTopicsProperties,
    private val transactionalOutboxRepository: TransactionalOutboxRepository,
    private val objectMapper: ObjectMapper,
) {
    /**
     * 아직 발행되지 않은 거래 성공 outbox를 Work 도메인 분석 토픽으로 발행합니다.
     */
    @Scheduled(fixedDelayString = "\${moaje.asset.kafka.outbox-publish-delay-ms:1000}")
    @Transactional
    fun publishPendingTransactionSucceededEvents() {
        val outboxes = transactionalOutboxRepository
            .findTop100ByAggregateTypeAndPublishedFalseAndDeliveryExcludedFalseOrderByCreatedAtAsc(TRANSACTION_SUCCEEDED)

        outboxes.forEach { outbox ->
            val payload = objectMapper.readValue(outbox.payload, TransactionSucceededOutboxPayload::class.java)
            // 이전 버전이 이미 쌓아 둔 반전 이벤트도 Work로 보내지 않는다. DB에서 정책 제외로 따로 보존한다.
            // PUBLISHED는 실제 broker 확인을 뜻하므로, 전송하지 않은 레코드를 발행 성공으로 표시하면 안 된다.
            if (payload.transactionType == "REVERSAL" || payload.timestampSource != "CORE_BANKING") {
                outbox.excludeFromDelivery()
                return@forEach
            }
            val event = payload.toProtoEvent()

            kafkaTemplate
                .send(topics.transactionSucceeded, payload.transactionId.toString(), event.toByteArray())
                .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            outbox.markPublished()
        }
    }

    @Scheduled(fixedDelayString = "\${moaje.asset.kafka.aggregate-outbox-publish-delay-ms:1000}")
    @Transactional
    fun publishPendingCashflowEvents() {
        publishMonthlyCashflows()
        publishCategoryCashflows()
    }

    private fun publishMonthlyCashflows() {
        transactionalOutboxRepository
            .findTop100ByAggregateTypeAndPublishedFalseOrderByCreatedAtAsc(
                MonthlyCashflowServiceImpl.MONTHLY_CASHFLOW_AGGREGATED,
            )
            .forEach { outbox ->
                val payload = objectMapper.readValue(outbox.payload, MonthlyCashflowOutboxPayload::class.java)
                val event = MonthlyCashflowAggregatedEvent.newBuilder()
                    .setEventId(payload.eventId)
                    .setUserId(payload.userId)
                    .setYearMonth(payload.yearMonth)
                    .setRevision(payload.revision)
                    .setIncomeAmount(payload.incomeAmount.toProtoMoney("KRW"))
                    .setExpenseAmount(payload.expenseAmount.toProtoMoney("KRW"))
                    .setTransferAmount(payload.transferAmount.toProtoMoney("KRW"))
                    .setCalculatedAt(payload.calculatedAt.toString())
                    .build()
                sendAndMarkPublished(outbox, topics.monthlyCashflowAggregated, payload.userId, event.toByteArray())
            }
    }

    private fun publishCategoryCashflows() {
        transactionalOutboxRepository
            .findTop100ByAggregateTypeAndPublishedFalseOrderByCreatedAtAsc(
                MonthlyCashflowServiceImpl.CATEGORY_CASHFLOW_AGGREGATED,
            )
            .forEach { outbox ->
                val payload = objectMapper.readValue(outbox.payload, CategoryCashflowOutboxPayload::class.java)
                val event = CategoryCashflowAggregatedEvent.newBuilder()
                    .setEventId(payload.eventId)
                    .setUserId(payload.userId)
                    .setYearMonth(payload.yearMonth)
                    .setRevision(payload.revision)
                    .addAllCategories(payload.categories.map { item ->
                        CategoryCashflowAmount.newBuilder()
                            .setCategory(item.category)
                            .setAmount(item.amount.toProtoMoney("KRW"))
                            .build()
                    })
                    .setCalculatedAt(payload.calculatedAt.toString())
                    .build()
                sendAndMarkPublished(outbox, topics.categoryCashflowAggregated, payload.userId, event.toByteArray())
            }
    }

    private fun sendAndMarkPublished(
        outbox: com.moaje.asset.outbox.domain.TransactionalOutbox,
        topic: String,
        key: String,
        value: ByteArray,
    ) {
        kafkaTemplate.send(topic, key, value).get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        outbox.markPublished()
    }

    private fun TransactionSucceededOutboxPayload.toProtoEvent(): TransactionSucceededEvent {
        return TransactionSucceededEvent.newBuilder()
            .setEventId(eventId)
            .setTransactionId(transactionId)
            .setPublicTransactionId(publicTransactionId)
            .setUserId(userId)
            .setAccountId(accountId)
            .setExternalTransactionId(externalTransactionId.orEmpty())
            .setTransactionType(transactionType)
            .setAmount(amount.toProtoMoney(currency))
            .setTargetToken(targetToken.orEmpty())
            .setSnapshotBalance(balanceAfterTransaction.toProtoMoney(currency))
            .setSnapshotAsOf(snapshotAsOf?.toString().orEmpty())
            .setSucceededAt(succeededAt?.toString().orEmpty())
            .setOccurredAt(occurredAt.toString())
            .setRecoveredAt(recoveredAt?.toString().orEmpty())
            .setRecordedAt(recordedAt?.toString().orEmpty())
            .setExternalType(externalType.orEmpty())
            .setTimestampSource(timestampSource)
            .build()
    }

    private fun java.math.BigDecimal.toProtoMoney(currency: String): ProtoMoney {
        return ProtoMoney.newBuilder()
            .setAmount(toLong())
            .setCurrency(currency)
            .build()
    }

    companion object {
        const val TRANSACTION_SUCCEEDED = "TRANSACTION_SUCCEEDED"
        private const val KAFKA_SEND_TIMEOUT_SECONDS = 5L
    }
}

