package com.moaje.asset.account.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.moaje.asset.account.application.gateway.BankingAccountProjectionGateway
import com.moaje.asset.account.application.usecase.AccountTransactionListCommand
import com.moaje.asset.account.domain.Account
import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.transfer.application.service.TransferApplicationService
import com.moaje.asset.transfer.domain.TransactionHistory
import com.moaje.asset.transfer.domain.TransactionStatus
import com.moaje.asset.transfer.domain.TransactionType
import com.moaje.asset.transfer.persistence.TransactionHistoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.domain.PageRequest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.Optional

class AccountDetailQueryServicePaginationTest {
    private val accountRepository = Mockito.mock(AccountRepository::class.java)
    private val transactionHistoryRepository = Mockito.mock(TransactionHistoryRepository::class.java)
    private val service = AccountDetailQueryService(
        accountRepository = accountRepository,
        transactionHistoryRepository = transactionHistoryRepository,
        redisTemplate = Mockito.mock(StringRedisTemplate::class.java),
        objectMapper = ObjectMapper(),
        bankingGateway = Mockito.mock(BankingAccountProjectionGateway::class.java),
        transferApplicationService = Mockito.mock(TransferApplicationService::class.java),
        transactionTemplate = Mockito.mock(TransactionTemplate::class.java),
    )

    @Nested
    @DisplayName("거래내역 커서 조회")
    inner class TransactionCursorPagination {
        @Test
        @DisplayName("최초 요청은 커서 없이 51건을 확인하고 50건만 반환한다")
        fun firstPageReturnsFiftyItemsAndNextCursor() {
            // given: 다음 페이지가 있는지 판단할 수 있도록 Repository가 51건을 반환한다.
            givenOwnedAccount()
            Mockito.`when`(
                transactionHistoryRepository.findVisibleTransactionsByCursor(
                    1L,
                    null,
                    PageRequest.of(0, 51),
                ),
            ).thenReturn((100L downTo 50L).map(::history))

            // when
            val result = service.getTranList(command(lastTransactionId = null))

            // then: 51번째 Row는 탐색용이므로 응답에서 제외하고 50번째 ID를 다음 커서로 사용한다.
            assertThat(result.transactions).hasSize(50)
            assertThat(result.transactions.first().transactionId).isEqualTo(100L)
            assertThat(result.transactions.last().transactionId).isEqualTo(51L)
            assertThat(result.hasNext).isTrue()
            assertThat(result.nextCursor).isEqualTo(51L)

            Mockito.verify(transactionHistoryRepository).findVisibleTransactionsByCursor(
                1L,
                null,
                PageRequest.of(0, 51),
            )
        }

        @Test
        @DisplayName("마지막 페이지는 다음 커서를 반환하지 않는다")
        fun lastPageHasNoNextCursor() {
            // given
            givenOwnedAccount()
            Mockito.`when`(
                transactionHistoryRepository.findVisibleTransactionsByCursor(
                    1L,
                    51L,
                    PageRequest.of(0, 51),
                ),
            ).thenReturn((50L downTo 1L).map(::history))

            // when
            val result = service.getTranList(command(lastTransactionId = 51L))

            // then: 50건 이하라면 추가 조회가 필요하지 않다.
            assertThat(result.transactions).hasSize(50)
            assertThat(result.hasNext).isFalse()
            assertThat(result.nextCursor).isNull()
        }

        @Test
        @DisplayName("다른 사용자의 계좌는 거래내역을 조회하지 않는다")
        fun rejectsAnotherUsersAccount() {
            // given
            Mockito.`when`(accountRepository.findById(1L))
                .thenReturn(Optional.of(Account(id = 1L, userId = "other-user")))

            // when / then: 커서 조회 전에 계좌 소유권을 먼저 확인한다.
            assertThatThrownBy { service.getTranList(command(lastTransactionId = null)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("사용자의 계좌가 아닙니다.")
            Mockito.verifyNoInteractions(transactionHistoryRepository)
        }
    }

    private fun givenOwnedAccount() {
        Mockito.`when`(accountRepository.findById(1L))
            .thenReturn(Optional.of(Account(id = 1L, userId = "user-1")))
    }

    private fun command(lastTransactionId: Long?) = AccountTransactionListCommand(
        userId = "user-1",
        accountId = 1L,
        lastTransactionId = lastTransactionId,
    )

    private fun history(id: Long) = TransactionHistory(
        id = id,
        accountId = 1L,
        publicTransferId = "TX-$id",
        type = TransactionType.TRANSFER,
        amount = BigDecimal("1000"),
        status = TransactionStatus.SUCCESS,
        idempotencyKey = "KEY-$id",
    )
}
