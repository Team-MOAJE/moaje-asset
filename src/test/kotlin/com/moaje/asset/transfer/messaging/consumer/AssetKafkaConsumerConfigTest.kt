package com.moaje.asset.transfer.messaging.consumer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DefaultErrorHandler

class AssetKafkaConsumerConfigTest {
    @Test
    @DisplayName("자동 시작을 끈 테스트 Listener는 실제 Kafka를 소비하지 않는다")
    fun respectsDisabledAutoStartup() {
        // given: 컨테이너만 구성하고 시작하지 않으므로 실제 브로커 접속이 필요 없다.
        val consumerFactory = org.springframework.kafka.core.DefaultKafkaConsumerFactory<String, ByteArray>(
            mapOf(
                "bootstrap.servers" to "localhost:1",
                "key.deserializer" to org.apache.kafka.common.serialization.StringDeserializer::class.java,
                "value.deserializer" to org.apache.kafka.common.serialization.ByteArrayDeserializer::class.java,
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val template = Mockito.mock(KafkaTemplate::class.java) as KafkaTemplate<String, ByteArray>
        val marker = Mockito.mock(DltProjectionRecoveryMarker::class.java)

        // when: Spring 설정의 false가 Listener Container까지 전달되는지 확인한다.
        val factory = AssetKafkaConsumerConfig().kafkaListenerContainerFactory(
            consumerFactory, template, marker, "isolated-test", autoStartup = false,
        )
        val container = factory.createContainer("isolated-test-topic")

        // then: 일반 컨텍스트 테스트가 개발 Consumer Group의 offset을 이동시키지 않게 한다.
        assertThat(container.isAutoStartup).isFalse()
        assertThat(container.isRunning).isFalse()
    }

    @Test
    @DisplayName("Asset Kafka Listener는 제한 재시도 후 DLT로 보내는 ErrorHandler를 사용한다")
    @Suppress("UNCHECKED_CAST")
    fun kafkaListenerFactoryUsesDefaultErrorHandlerForRetryAndDlt() {
        // given: 현재 프로젝트의 Spring Kafka 기능만 사용해 Listener Factory를 만든다.
        val consumerFactory = Mockito.mock(ConsumerFactory::class.java) as ConsumerFactory<String, ByteArray>
        val kafkaTemplate = Mockito.mock(KafkaTemplate::class.java) as KafkaTemplate<String, ByteArray>
        val marker = Mockito.mock(DltProjectionRecoveryMarker::class.java)

        // when: Asset consumer 설정을 생성한다.
        val factory = AssetKafkaConsumerConfig()
            .kafkaListenerContainerFactory(consumerFactory, kafkaTemplate, marker, "test-asset")

        // then: Listener 정상 반환 전 DB 트랜잭션 예외가 발생하면 common ErrorHandler가 retry/DLT 정책을 담당한다.
        assertThat(factory.containerProperties.groupId).isEqualTo("test-asset")
        val errorHandlerField = factory.javaClass.superclass.getDeclaredField("commonErrorHandler")
        errorHandlerField.isAccessible = true
        assertThat(errorHandlerField.get(factory)).isInstanceOf(DefaultErrorHandler::class.java)
    }
}
