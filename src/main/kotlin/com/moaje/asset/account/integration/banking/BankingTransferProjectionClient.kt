package com.moaje.asset.account.integration.banking

import com.moaje.asset.account.application.gateway.BankingProjectionTransferStatus
import com.moaje.asset.account.application.gateway.BankingAccountProjectionGateway
import com.moaje.asset.account.application.gateway.BankingAccountProjectionSnapshot
import com.moaje.asset.account.application.gateway.BankingExternalProjectionTransaction
import com.moaje.asset.account.application.gateway.BankingTransferProjectionGateway
import com.moaje.asset.account.application.gateway.BankingTransferProjectionState
import com.moaje.grpc.banking.BankingServiceGrpc
import com.moaje.grpc.banking.GetTransferProjectionStatesRequest
import com.moaje.grpc.banking.GetAccountProjectionSnapshotRequest
import com.moaje.grpc.banking.ExternalProjectionTransaction
import com.moaje.grpc.banking.TransferProjectionState
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.io.File
import java.util.concurrent.TimeUnit

@Component
class BankingTransferProjectionClient(
    private val properties: BankingGrpcClientProperties,
) : BankingTransferProjectionGateway, BankingAccountProjectionGateway, DisposableBean {
    private val channelDelegate = lazy {
        buildChannel()
    }
    private val channel: ManagedChannel by channelDelegate
    private val client: BankingServiceGrpc.BankingServiceBlockingStub by lazy {
        BankingServiceGrpc.newBlockingStub(channel)
    }

    /**
     * 외부 호출에는 deadline을 둬 Worker가 무한히 lease를 점유하지 않게 한다.
     * Timeout은 거래 실패가 아니라 조회 실패이므로 상위 대사 서비스가 재시도 상태로 기록한다.
     */
    override fun getTransferStates(
        accountId: Long,
        transferIds: Collection<Long>,
    ): List<BankingTransferProjectionState> {
        val request = GetTransferProjectionStatesRequest.newBuilder()
            .setAccountId(accountId)
            .addAllTransferIds(transferIds)
            .build()
        return client.withDeadlineAfter(properties.projectionReconciliationTimeoutMs, TimeUnit.MILLISECONDS)
            .getTransferProjectionStates(request)
            .transfersList
            .map { it.toDomain() }
    }

    /**
     * cursor 이후 이력과 그 조회 시점의 기준 잔액을 함께 받아 Projection을 절대값으로 검증한다.
     * gRPC deadline을 lease보다 짧게 제한해야 멈춘 원격 호출이 계좌 선점을 끝없이 붙잡지 않는다.
     */
    override fun getAccountSnapshot(accountId: Long, cursor: Instant): BankingAccountProjectionSnapshot {
        val response = client.withDeadlineAfter(properties.projectionReconciliationTimeoutMs, TimeUnit.MILLISECONDS)
            .getAccountProjectionSnapshot(
                GetAccountProjectionSnapshotRequest.newBuilder()
                    .setAccountId(accountId)
                    .setCursorEpochMillis(cursor.toEpochMilli().coerceAtLeast(0))
                    .build(),
            )
        return BankingAccountProjectionSnapshot(
            accountId = response.accountId,
            principalId = response.principalId,
            balance = BigDecimal.valueOf(response.balance.amount),
            currency = response.balance.currency,
            accountStatus = response.accountStatus,
            asOf = Instant.ofEpochMilli(response.asOfEpochMillis),
            transactions = response.transactionsList.map { it.toDomain() },
        )
    }

    override fun destroy() {
        // 설정 검증 중 채널 생성이 실패한 경우 종료 훅이 채널을 다시 만들지 않아야 최초 원인을 가리지 않는다.
        if (channelDelegate.isInitialized()) {
            channelDelegate.value.shutdown()
        }
    }

    /**
     * Asset은 Banking 서버 인증서를 검증하는 동시에 자신의 client certificate도 제시한다.
     * TLS 설정이 켜진 상태에서 인증서가 누락되면 plaintext로 우회하지 않고 첫 연결 생성 단계에서 실패한다.
     */
    private fun buildChannel(): ManagedChannel {
        val builder = NettyChannelBuilder.forAddress(properties.grpcHost, properties.grpcPort)
        if (!properties.tls.enabled) return builder.usePlaintext().build()

        val tls = properties.tls
        builder.sslContext(
            GrpcSslContexts.forClient()
                .trustManager(tls.trustCertificateCollectionPath.requireReadableFile("Banking server trust certificates"))
                .keyManager(
                    tls.certificateChainPath.requireReadableFile("Asset client certificate chain"),
                    tls.privateKeyPath.requireReadableFile("Asset client private key"),
                )
                .build(),
        )
        tls.overrideAuthority.takeIf(String::isNotBlank)?.let(builder::overrideAuthority)
        return builder.build()
    }

    private fun String.requireReadableFile(description: String): File {
        require(isNotBlank()) { "$description 경로는 필수입니다." }
        return File(this).also { file ->
            require(file.isFile && file.canRead()) { "$description 파일을 읽을 수 없습니다. path=${file.path}" }
        }
    }

    private fun TransferProjectionState.toDomain(): BankingTransferProjectionState {
        require(status != TransferProjectionState.Status.STATUS_UNSPECIFIED) {
            "Banking 거래 상태가 지정되지 않았습니다. transferId=$transferId"
        }
        return BankingTransferProjectionState(
            transferId = transferId,
            principalId = principalId,
            accountId = accountId,
            amount = BigDecimal.valueOf(amount.amount),
            currency = amount.currency,
            status = BankingProjectionTransferStatus.valueOf(status.name),
            externalTransactionId = externalTransactionId.takeIf(String::isNotBlank),
            externalReversalTransactionId = externalReversalTransactionId.takeIf(String::isNotBlank),
            failureCode = failureCode.takeIf(String::isNotBlank),
            failureReason = failureReason.takeIf(String::isNotBlank),
            reversalReason = reversalReason.takeIf(String::isNotBlank),
        )
    }

    private fun ExternalProjectionTransaction.toDomain(): BankingExternalProjectionTransaction =
        BankingExternalProjectionTransaction(
            externalTransactionId = externalTransactionId,
            type = type,
            amount = BigDecimal.valueOf(amount.amount),
            currency = amount.currency,
            occurredAt = Instant.ofEpochMilli(occurredAtEpochMillis),
            completedAt = if (hasCompletedAtEpochMillis()) Instant.ofEpochMilli(completedAtEpochMillis) else null,
        )
}
