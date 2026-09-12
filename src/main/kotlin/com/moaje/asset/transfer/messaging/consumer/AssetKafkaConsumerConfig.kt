package com.moaje.asset.transfer.messaging.consumer

import com.google.protobuf.InvalidProtocolBufferException
import org.apache.kafka.common.TopicPartition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.util.backoff.FixedBackOff

@Configuration
class AssetKafkaConsumerConfig {
    @Bean
    fun kafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, ByteArray>,
        kafkaTemplate: KafkaTemplate<String, ByteArray>,
        dltProjectionRecoveryMarker: DltProjectionRecoveryMarker,
        @Value("\${spring.kafka.consumer.group-id:moaje-asset}") groupId: String,
        @Value("\${spring.kafka.listener.auto-startup:true}") autoStartup: Boolean = true,
    ): ConcurrentKafkaListenerContainerFactory<String, ByteArray> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, ByteArray>()
        factory.consumerFactory = consumerFactory
        factory.containerProperties.setGroupId(groupId)
        // 직접 만든 Factory에는 Boot의 Listener 설정이 자동으로 복사되지 않는다.
        // 테스트의 auto-startup=false를 반영해 실제 개발 Kafka의 메시지와 offset을 건드리지 않게 한다.
        factory.setAutoStartup(autoStartup)
        factory.setCommonErrorHandler(assetConsumerErrorHandler(kafkaTemplate, dltProjectionRecoveryMarker))
        return factory
    }

    private fun assetConsumerErrorHandler(
        kafkaTemplate: KafkaTemplate<String, ByteArray>,
        dltProjectionRecoveryMarker: DltProjectionRecoveryMarker,
    ): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { record, _ ->
            TopicPartition("${record.topic()}.DLT", record.partition())
        }
        // DLT broker ack를 확인하지 못하면 marker를 실행하지 않고 예외를 되던져 원본 offset의 정상 완료를 막는다.
        recoverer.setFailIfSendResultIsError(true)
        val recoverAndMark = { record: org.apache.kafka.clients.consumer.ConsumerRecord<*, *>, exception: Exception ->
            recoverer.accept(record, exception)
            dltProjectionRecoveryMarker.markAfterDltPublished(record)
        }
        return DefaultErrorHandler(recoverAndMark, FixedBackOff(RETRY_BACKOFF_MS, MAX_RETRY_ATTEMPTS)).apply {
            addNotRetryableExceptions(InvalidProtocolBufferException::class.java)
        }
    }

    companion object {
        private const val RETRY_BACKOFF_MS = 1_000L
        private const val MAX_RETRY_ATTEMPTS = 3L
    }
}
