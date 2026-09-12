package com.moaje.asset.transfer.messaging.consumer

import com.moaje.asset.account.persistence.AccountRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class DltProjectionRecoveryMarker(
    private val accountRepository: AccountRepository,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val routed = Counter.builder("asset.consumer.dlt.reconciliation.routed").register(meterRegistry)
    private val unroutable = Counter.builder("asset.consumer.dlt.reconciliation.unroutable").register(meterRegistry)

    /**
     * 역직렬화 실패 시 payload의 transferId는 읽을 수 없으므로 Producer가 보존한 accountId Header를 먼저 사용한다.
     * 식별 가능한 기존 계좌만 SYNC_REQUIRED로 바꾸며, 식별 불가 레코드는 추측하지 않고 주기적 전체 Snapshot 감사를 기다린다.
     */
    @Transactional
    fun markAfterDltPublished(record: ConsumerRecord<*, *>) {
        val accountId = record.headers().lastHeader(ACCOUNT_ID_HEADER)
            ?.value()
            ?.decodeToString()
            ?.toLongOrNull()
            ?: record.key()?.toString()?.toLongOrNull()
        if (accountId == null) {
            unroutable.increment()
            log.error(
                "DLT 이벤트에서 accountId를 식별할 수 없습니다. topic={}, partition={}, offset={}",
                record.topic(),
                record.partition(),
                record.offset(),
            )
            return
        }

        val account = accountRepository.findByIdForUpdate(accountId)
        if (account == null) {
            // 계좌 생성 이벤트 자체가 깨진 경우 Asset Row가 없으므로 DLT 원본을 수정·재처리해야 한다.
            unroutable.increment()
            log.error("DLT 이벤트의 Asset 계좌가 아직 없습니다. accountId={}", accountId)
            return
        }
        account.markFullSyncRequired()
        routed.increment()
    }

    companion object {
        const val ACCOUNT_ID_HEADER = "moaje-account-id"
    }
}
