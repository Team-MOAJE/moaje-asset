package com.moaje.asset.account.messaging.consumer

import com.moaje.asset.account.domain.AccountSyncStatus
import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.transfer.persistence.ProcessedEventRepository
import com.moaje.events.banking.AccountCreatedEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest

@DataJpaTest
@DisplayName("Banking 계좌 생성 이벤트 소비")
class BankingAccountEventConsumerJpaTest(
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val processedEventRepository: ProcessedEventRepository,
) {
    private val consumer by lazy { BankingAccountEventConsumer(accountRepository, processedEventRepository) }

    @Test
    @DisplayName("Banking accountId를 그대로 사용해 Asset Projection을 생성하고 중복 이벤트는 무시한다")
    fun createsProjectionWithStableAccountIdOnce() {
        // given
        val payload = AccountCreatedEvent.newBuilder()
            .setEventId("account-event-1")
            .setAccountId(7001L)
            .setUserId("user-1")
            .setBankCode("088")
            .setProductName("모아제 통장")
            .setInitialBalance(10_000L)
            .setCreatedAt("2026-01-01T00:00:00Z")
            .setOccurredAt("2026-01-01T00:00:01Z")
            .build()
            .toByteArray()

        // when: Kafka at-least-once 전달을 재현해 같은 이벤트를 두 번 소비한다.
        consumer.consumeAccountCreated(payload)
        consumer.consumeAccountCreated(payload)

        // then: Banking과 동일한 accountId 한 건만 남고 첫 Snapshot 확인 대상으로 표시된다.
        val account = accountRepository.findById(7001L).orElseThrow()
        assertThat(accountRepository.count()).isEqualTo(1)
        assertThat(account.userId).isEqualTo("user-1")
        assertThat(account.balance).isEqualByComparingTo("10000")
        assertThat(account.syncStatus).isEqualTo(AccountSyncStatus.SYNC_REQUIRED)
        assertThat(processedEventRepository.count()).isEqualTo(1)
    }
}
