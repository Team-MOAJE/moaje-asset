package com.moaje.asset.account.messaging.consumer

import com.moaje.asset.account.domain.Account
import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.transfer.domain.ProcessedEvent
import com.moaje.asset.transfer.persistence.ProcessedEventRepository
import com.moaje.events.banking.AccountCreatedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Component
class BankingAccountEventConsumer(
    private val accountRepository: AccountRepository,
    private val processedEventRepository: ProcessedEventRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Account와 processed_event를 한 트랜잭션에 저장해 재전달된 이벤트가 Projection을 중복 생성하지 않게 한다.
     * 신규 계좌는 SYNC_REQUIRED로 시작하며 첫 Snapshot 대사가 이벤트 전달 전후의 누락 거래와 잔액 차이를 확인한다.
     */
    @KafkaListener(topics = ["\${moaje.asset.kafka.topics.banking-account-created}"])
    @Transactional
    fun consumeAccountCreated(payload: ByteArray) {
        val event = AccountCreatedEvent.parseFrom(payload)
        require(event.eventId.isNotBlank()) { "AccountCreated eventId는 필수입니다." }
        require(event.accountId > 0) { "AccountCreated accountId는 양수여야 합니다." }
        require(event.userId.isNotBlank()) { "AccountCreated userId는 필수입니다." }

        if (processedEventRepository.existsById(event.eventId)) return

        val existing = accountRepository.findById(event.accountId).orElse(null)
        if (existing == null) {
            val account = Account(
                id = event.accountId,
                userId = event.userId,
                balance = BigDecimal.valueOf(event.initialBalance),
            )
            account.markSyncRequired()
            accountRepository.save(account)
        } else {
            // 같은 accountId를 다른 사용자에게 연결한 이벤트는 중복이 아니라 계약 위반이므로 DLT 대상으로 실패시킨다.
            require(existing.userId == event.userId) { "AccountCreated 계좌 소유자가 기존 Projection과 다릅니다." }
        }

        processedEventRepository.save(
            ProcessedEvent(
                eventId = event.eventId,
                eventType = "AccountCreatedEvent",
                accountId = event.accountId,
            ),
        )

        log.info(
            "계좌생성 Event를 성공적으로 소비하였습니다. eventId={}, accountId={}, bankCode={}, productName={}",
            event.eventId,
            event.accountId,
            event.bankCode,
            event.productName,
        )
    }
}
