package com.moaje.asset.domain.account

import com.moaje.asset.common.id.TsidGeneratedValue
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(
    name = "account",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_account_account_token", columnNames = ["account_token"]),
    ],
    indexes = [
        Index(name = "idx_account_user_id", columnList = "user_id"),
    ],
)
class Account(
    @Id
    @TsidGeneratedValue
    @Column(name = "id", nullable = false)
    var id: Long? = null,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "account_token", nullable = false, length = 255)
    var accountToken: String,

    @Column(name = "balance", nullable = false, precision = 18, scale = 4)
    var balance: BigDecimal = BigDecimal.ZERO,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: AccountStatus = AccountStatus.ACTIVE,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun requireTransferable() {
        require(status == AccountStatus.ACTIVE) { "활성 계좌만 거래할 수 있습니다." }
    }

    fun reserve(amount: BigDecimal) {
        requireTransferable()
        require(amount > BigDecimal.ZERO) { "거래 금액은 0보다 커야 합니다." }
        require(balance >= amount) { "가용 잔액이 부족합니다." }
        balance = balance.subtract(amount)
    }

    fun restore(amount: BigDecimal) {
        require(amount > BigDecimal.ZERO) { "복구 금액은 0보다 커야 합니다." }
        balance = balance.add(amount)
    }

    fun deposit(amount: BigDecimal) {
        requireTransferable()
        require(amount > BigDecimal.ZERO) { "입금 금액은 0보다 커야 합니다." }
        balance = balance.add(amount)
    }

    fun withdraw(amount: BigDecimal) {
        reserve(amount)
    }
}

