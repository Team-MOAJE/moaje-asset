package com.moaje.asset.transfer.persistence

import com.moaje.asset.transfer.domain.TransactionHistory
import com.moaje.asset.transfer.domain.TransactionStatus
import com.moaje.asset.transfer.domain.TransactionType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.data.domain.PageRequest
import java.math.BigDecimal

@DataJpaTest
class TransactionHistoryCursorRepositoryTest(
    @Autowired private val repository: TransactionHistoryRepository,
) {
    private lateinit var visibleIdsDescending: List<Long>

    @BeforeEach
    fun setUp() {
        val saved = repository.saveAllAndFlush(
            listOf(
                history(sequence = 1),
                history(sequence = 2),
                history(sequence = 3),
                history(sequence = 4, visibleToUser = false),
            ),
        )
        visibleIdsDescending = saved
            .filter { it.visibleToUser }
            .map { requireNotNull(it.id) }
            .sortedDescending()
    }

    @Test
    @DisplayName("최초 요청은 보이는 거래만 ID 내림차순으로 조회한다")
    fun firstPageUsesDescendingTransactionId() {
        // given / when: 최초 요청이므로 마지막 거래 ID를 전달하지 않는다.
        val result = repository.findVisibleTransactionsByCursor(
            accountId = 1L,
            lastTransactionId = null,
            pageable = PageRequest.of(0, 10),
        )

        // then: 사용자에게 숨긴 거래는 제외된다.
        assertThat(result.map { it.id }).containsExactlyElementsOf(visibleIdsDescending)
    }

    @Test
    @DisplayName("다음 요청은 마지막으로 받은 거래 ID보다 작은 거래만 조회한다")
    fun nextPageStartsAfterCursor() {
        // given / when: 이전 페이지의 마지막 거래 ID를 커서로 넘긴다.
        val cursor = visibleIdsDescending[1]
        val result = repository.findVisibleTransactionsByCursor(
            accountId = 1L,
            lastTransactionId = cursor,
            pageable = PageRequest.of(0, 10),
        )

        // then: 커서를 다시 포함하지 않고 그보다 작은 ID만 반환한다.
        assertThat(result.map { it.id })
            .containsExactlyElementsOf(visibleIdsDescending.filter { it < cursor })
    }

    private fun history(sequence: Int, visibleToUser: Boolean = true) = TransactionHistory(
        accountId = 1L,
        publicTransferId = "TX-$sequence",
        type = TransactionType.TRANSFER,
        amount = BigDecimal("1000"),
        status = TransactionStatus.SUCCESS,
        idempotencyKey = "KEY-$sequence",
        visibleToUser = visibleToUser,
    )
}
