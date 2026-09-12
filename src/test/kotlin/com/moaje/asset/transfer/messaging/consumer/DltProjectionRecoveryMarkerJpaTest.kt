package com.moaje.asset.transfer.messaging.consumer

import com.moaje.asset.account.domain.Account
import com.moaje.asset.account.domain.AccountSyncStatus
import com.moaje.asset.account.persistence.AccountRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest

@DataJpaTest
@DisplayName("transferId를 읽을 수 없는 DLT 복구 표식")
class DltProjectionRecoveryMarkerJpaTest(
    @Autowired private val accountRepository: AccountRepository,
) {
    @Test
    @DisplayName("손상된 payload를 해석하지 않고 accountId Header로 계좌를 SYNC_REQUIRED로 만든다")
    fun marksAccountUsingHeaderWithoutParsingPayload() {
        // given: Protobuf가 손상돼 transferId를 알 수 없지만 Producer Header에는 accountId가 남아 있다.
        val account = accountRepository.saveAndFlush(Account(userId = "user-1"))
        account.markSynced()
        val headers = RecordHeaders(
            listOf(RecordHeader(DltProjectionRecoveryMarker.ACCOUNT_ID_HEADER, account.id.toString().toByteArray())),
        )
        val record = ConsumerRecord(
            "moaje.banking.transfer-completed",
            0,
            10L,
            System.currentTimeMillis(),
            org.apache.kafka.common.record.TimestampType.CREATE_TIME,
            0,
            0,
            account.id.toString(),
            byteArrayOf(0x01, 0x02),
            headers,
            java.util.Optional.empty(),
        )
        val marker = DltProjectionRecoveryMarker(accountRepository, SimpleMeterRegistry())

        // when: DLT 발행 성공 뒤 호출되는 표식 단계만 실행한다.
        marker.markAfterDltPublished(record)

        // then: payload 파싱 없이 계좌 단위 Snapshot 복구 대상으로 전환된다.
        assertThat(accountRepository.findById(account.id!!).orElseThrow().syncStatus)
            .isEqualTo(AccountSyncStatus.SYNC_REQUIRED)
        assertThat(accountRepository.findById(account.id!!).orElseThrow().lastSyncedAt).isNull()
    }
}
