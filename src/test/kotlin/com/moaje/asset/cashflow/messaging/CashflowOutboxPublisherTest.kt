package com.moaje.asset.cashflow.messaging

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.moaje.asset.cashflow.application.service.CategoryCashflowOutboxItem
import com.moaje.asset.cashflow.application.service.CategoryCashflowOutboxPayload
import com.moaje.asset.cashflow.application.service.MonthlyCashflowAggregationService
import com.moaje.asset.cashflow.application.service.MonthlyCashflowOutboxPayload
import com.moaje.asset.outbox.domain.TransactionalOutbox
import com.moaje.asset.outbox.persistence.TransactionalOutboxRepository
import com.moaje.asset.transfer.messaging.publisher.AssetKafkaTopicsProperties
import com.moaje.asset.transfer.messaging.publisher.AssetOutboxKafkaPublisher
import com.moaje.events.asset.CategoryCashflowAggregatedEvent
import com.moaje.events.asset.MonthlyCashflowAggregatedEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

class CashflowOutboxPublisherTest {
    @Test
    @DisplayName("월별·카테고리 집계 Outbox를 각각의 Work 토픽으로 발행한다")
    fun publishesAggregateEventsToSeparateTopics() {
        // given
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val repository = mock(TransactionalOutboxRepository::class.java)
        @Suppress("UNCHECKED_CAST")
        val kafka = mock(KafkaTemplate::class.java) as KafkaTemplate<String, ByteArray>
        val now = LocalDateTime.of(2026, 9, 1, 0, 10)
        val monthly = TransactionalOutbox(
            aggregateType = MonthlyCashflowAggregationService.MONTHLY_CASHFLOW_AGGREGATED,
            aggregateId = 1L,
            payload = mapper.writeValueAsString(
                MonthlyCashflowOutboxPayload("monthly-event", "1001", "2026-08", 1, BigDecimal("100"),
                    BigDecimal("40"), BigDecimal("10"), now),
            ),
        )
        val category = TransactionalOutbox(
            aggregateType = MonthlyCashflowAggregationService.CATEGORY_CASHFLOW_AGGREGATED,
            aggregateId = 1L,
            payload = mapper.writeValueAsString(
                CategoryCashflowOutboxPayload("category-event", "1001", "2026-08", 1,
                    listOf(CategoryCashflowOutboxItem("CAFE", BigDecimal("40"))), now),
            ),
        )
        `when`(repository.findTop100ByAggregateTypeAndPublishedFalseOrderByCreatedAtAsc(
            MonthlyCashflowAggregationService.MONTHLY_CASHFLOW_AGGREGATED)).thenReturn(listOf(monthly))
        `when`(repository.findTop100ByAggregateTypeAndPublishedFalseOrderByCreatedAtAsc(
            MonthlyCashflowAggregationService.CATEGORY_CASHFLOW_AGGREGATED)).thenReturn(listOf(category))
        @Suppress("UNCHECKED_CAST")
        `when`(kafka.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(ByteArray::class.java)))
            .thenReturn(CompletableFuture.completedFuture(mock(SendResult::class.java) as SendResult<String, ByteArray>))

        // when
        val topics = AssetKafkaTopicsProperties()
        AssetOutboxKafkaPublisher(kafka, topics, repository, mapper).publishPendingCashflowEvents()

        // then
        val topicCaptor = ArgumentCaptor.forClass(String::class.java)
        val keyCaptor = ArgumentCaptor.forClass(String::class.java)
        val valueCaptor = ArgumentCaptor.forClass(ByteArray::class.java)
        verify(kafka, times(2)).send(topicCaptor.capture(), keyCaptor.capture(), valueCaptor.capture())
        assertThat(topicCaptor.allValues).containsExactly(
            topics.monthlyCashflowAggregated,
            topics.categoryCashflowAggregated,
        )
        assertThat(keyCaptor.allValues).containsOnly("1001")
        assertThat(MonthlyCashflowAggregatedEvent.parseFrom(valueCaptor.allValues[0]).revision).isEqualTo(1)
        assertThat(CategoryCashflowAggregatedEvent.parseFrom(valueCaptor.allValues[1]).categoriesList.single().category)
            .isEqualTo("CAFE")
        assertThat(monthly.published).isTrue()
        assertThat(category.published).isTrue()
    }
}
