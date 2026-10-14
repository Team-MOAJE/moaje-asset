package com.moaje.asset.account.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.moaje.asset.account.application.model.AccountDetailCommand
import com.moaje.asset.account.application.query.BankingAccountSnapshotQuery
import com.moaje.asset.account.domain.Account
import com.moaje.asset.account.domain.AccountSyncStatus
import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.transfer.application.service.TransferServiceImpl
import com.moaje.asset.transfer.persistence.TransactionHistoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.PlatformTransactionManager
import com.moaje.asset.account.application.query.BankingAccountNumberQuery
import com.moaje.asset.account.exception.AccountAuthFailedException
import org.assertj.core.api.Assertions.assertThatThrownBy

@DataJpaTest
@DisplayName("계좌 목록과 상세의 은행 정보")
class AccountBankInformationJpaTest(
    @Autowired private val accounts: AccountRepository,
    @Autowired private val histories: TransactionHistoryRepository,
    @Autowired private val transactionManager: PlatformTransactionManager,
) {
    private val redis = Mockito.mock(StringRedisTemplate::class.java)
    @Suppress("UNCHECKED_CAST")
    private val values = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val numbers = Mockito.mock(BankingAccountNumberQuery::class.java)
    private val service by lazy {
        Mockito.`when`(redis.opsForValue()).thenReturn(values)
        Mockito.`when`(numbers.getOwnedAccountNumber(Mockito.anyLong(), Mockito.anyString())).thenReturn("12345678901234")
        AccountDetailServiceImpl(
            accounts, histories, redis, mapper,
            Mockito.mock(BankingAccountSnapshotQuery::class.java),
            Mockito.mock(TransferServiceImpl::class.java),
            TransactionTemplate(transactionManager),
            numbers,
        )
    }

    @Test
    @DisplayName("본인 계좌만 조회하고 은행 코드와 이름을 반환한다")
    fun listsOwnedAccountsWithBankInformation() {
        // given: 두 사용자의 계좌를 같은 DB에 저장한다.
        val own = accounts.saveAndFlush(Account(userId = "owner", bankCode = "088", bankName = "테스트 은행", maskedAccountNumber = "**********1234"))
        accounts.saveAndFlush(Account(userId = "other", bankCode = "004", bankName = "다른 은행"))
        // when
        val result = service.getAccounts("owner")
        // then: 다른 사용자 계좌는 노출되지 않는다.
        assertThat(result).hasSize(1)
        assertThat(result.single().accountId).isEqualTo(own.id)
        assertThat(result.single().bankCode).isEqualTo("088")
        assertThat(result.single().bankName).isEqualTo("테스트 은행")
        assertThat(result.single().maskedAccountNumber).isEqualTo("**********1234")
        assertThat(service.getAccounts("unlinked")).isEmpty()
        // Controller도 검증된 Principal을 사용하고 같은 정보를 JSON으로 전달한다.
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            com.moaje.asset.account.web.AccountController(service),
        ).build().perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/assets/accounts")
                .principal { "owner" },
        ).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].bankCode").value("088"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].bankName").value("테스트 은행"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].accountId").isString)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].maskedAccountNumber").value("**********1234"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].accountNumber").doesNotExist())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(1))
    }

    @Test
    @DisplayName("상세 응답에도 DB에 저장된 은행 정보를 채운다")
    fun detailIncludesBankInformation() {
        // given
        val account = accounts.saveAndFlush(Account(userId = "owner", bankCode = "088", bankName = "테스트 은행"))
        // when
        val result = service.getDetail(AccountDetailCommand("owner", account.id!!))
        // then
        assertThat(result.bankCode).isEqualTo("088")
        assertThat(result.bankName).isEqualTo("테스트 은행")
        assertThat(result.accountNumber).isEqualTo("12345678901234")
    }

    @Test
    @DisplayName("은행 정보가 없는 구버전 캐시에도 현재 DB의 은행 정보를 보충한다")
    fun staleCacheUsesCurrentBankInformation() {
        // given: 필드 추가 전 상세 캐시를 재현한다.
        val account = accounts.saveAndFlush(Account(userId = "owner", bankCode = "088", bankName = "테스트 은행"))
        val command = AccountDetailCommand("owner", account.id!!)
        val legacy = service.getDetail(command).copy(bankCode = null, bankName = null)
        Mockito.`when`(values.get("asset:account-detail:${account.id}")).thenReturn(mapper.writeValueAsString(legacy))
        account.markStale()
        // when
        val result = service.getDetail(command)
        // then: 잔액 캐시는 유지하더라도 은행 정보가 null로 사라지면 안 된다.
        assertThat(result.syncStatus).isEqualTo(AccountSyncStatus.STALE)
        assertThat(result.bankCode).isEqualTo("088")
        assertThat(result.bankName).isEqualTo("테스트 은행")
    }

    @Test
    @DisplayName("기존 계좌의 알 수 없는 은행명은 임의로 채우지 않는다")
    fun legacyAccountKeepsUnknownBankInformation() {
        // given
        accounts.saveAndFlush(Account(userId = "owner"))
        // when
        val result = service.getAccounts("owner").single()
        // then
        assertThat(result.bankCode).isNull()
        assertThat(result.bankName).isNull()
    }

    @Test
    @DisplayName("상세 원번호는 응답에만 포함하고 Redis 캐시에 저장하지 않는다")
    fun fullNumberIsNotCached() {
        // given
        val account = accounts.saveAndFlush(Account(userId = "owner"))
        // when
        val detail = service.getDetail(AccountDetailCommand("owner", account.id!!))
        // then: HTTP 결과에만 전체 번호가 있고 캐시의 accountNumber는 null이다.
        assertThat(detail.accountNumber).isEqualTo("12345678901234")
        val capture = org.mockito.ArgumentCaptor.forClass(String::class.java)
        Mockito.verify(values).set(Mockito.eq("asset:account-detail:${account.id}"), capture.capture(), Mockito.eq(1L), Mockito.eq(java.util.concurrent.TimeUnit.DAYS))
        assertThat(capture.value).doesNotContain("12345678901234")
        assertThat(mapper.readTree(capture.value)["accountNumber"].isNull).isTrue()
    }

    @Test
    @DisplayName("타인 계좌 상세는 400이고 원번호 RPC를 호출하지 않는다")
    fun rejectsForeignDetailBeforeRemoteCall() {
        // given
        val account = accounts.saveAndFlush(Account(userId = "other"))
        // when / then
        assertThatThrownBy { service.getDetail(AccountDetailCommand("owner", account.id!!)) }
            .isInstanceOfSatisfying(AccountAuthFailedException::class.java) { ex ->
                assertThat(ex.status.value()).isEqualTo(400)
            }
        Mockito.verifyNoInteractions(numbers)
    }
}
