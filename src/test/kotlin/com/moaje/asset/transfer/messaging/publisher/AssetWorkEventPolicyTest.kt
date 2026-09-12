package com.moaje.asset.transfer.messaging.publisher

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.moaje.asset.outbox.domain.TransactionalOutbox
import com.moaje.asset.outbox.persistence.TransactionalOutboxRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.kafka.core.KafkaTemplate
import java.math.BigDecimal
import java.time.Instant

class AssetWorkEventPolicyTest {
    @Test
    @DisplayName("이전 버전이 쌓아 둔 반전과 원시각 미확인 이벤트는 전송 성공으로 위장하지 않고 제외한다")
    fun excludesLegacyAndReversalWithoutPublishing() {
        // given
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val repository = mock(TransactionalOutboxRepository::class.java)
        @Suppress("UNCHECKED_CAST")
        val kafka = mock(KafkaTemplate::class.java) as KafkaTemplate<String, ByteArray>
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val base = TransactionSucceededOutboxPayload("event", 1L, "public", "user", 2L, "external", "TRANSFER",
            BigDecimal.ONE, targetToken = null, balanceAfterTransaction = BigDecimal.TEN, succeededAt = now, occurredAt = now)
        val rows = listOf(base, base.copy(transactionType = "REVERSAL", timestampSource = "CORE_BANKING"))
            .map { TransactionalOutbox(aggregateType = "TRANSACTION_SUCCEEDED", aggregateId = 1L,
                payload = mapper.writeValueAsString(it)) }
        `when`(repository.findTop100ByAggregateTypeAndPublishedFalseAndDeliveryExcludedFalseOrderByCreatedAtAsc("TRANSACTION_SUCCEEDED"))
            .thenReturn(rows)
        // when
        AssetOutboxKafkaPublisher(kafka, AssetKafkaTopicsProperties(), repository, mapper).publishPendingTransactionSucceededEvents()
        // then: broker ack가 없으므로 published는 false이고, 다시 자동 선택되지 않을 제외 표식만 남는다.
        verifyNoInteractions(kafka)
        assertThat(rows).allMatch { it.deliveryExcluded && !it.published }
    }
}
