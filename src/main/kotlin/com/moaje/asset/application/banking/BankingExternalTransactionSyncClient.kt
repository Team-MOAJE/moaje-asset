package com.moaje.asset.application.banking

import com.moaje.grpc.banking.BankingServiceGrpc
import com.moaje.grpc.banking.SyncExternalAccountTransactionsRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConfigurationProperties(prefix = "moaje.asset.banking")
class BankingGrpcClientProperties {
    var grpcHost: String = "localhost"
    var grpcPort: Int = 9091
}

data class ExternalTransactionSyncRequest(
    val userId: Long,
    val assetAccountId: Long,
    val accountToken: String,
    val externalAccountNumber: String,
    val ci: String,
    val userName: String,
    val phoneNumber: String,
    val cursor: Instant,
)

data class ExternalTransactionSyncResult(
    val syncedCount: Int,
)

@Component
class BankingExternalTransactionSyncClient(
    private val properties: BankingGrpcClientProperties,
) : DisposableBean {
    private val channel: ManagedChannel by lazy {
        ManagedChannelBuilder
            .forAddress(properties.grpcHost, properties.grpcPort)
            .usePlaintext()
            .build()
    }

    private val bankingServiceBlockingClient: BankingServiceGrpc.BankingServiceBlockingStub by lazy {
        BankingServiceGrpc.newBlockingStub(channel)
    }

    /**
     * Banking 서비스에 특정 계좌의 외부 금융망 거래내역 동기화를 요청합니다.
     */
    fun syncExternalAccountTransactions(request: ExternalTransactionSyncRequest): ExternalTransactionSyncResult {
        val response = bankingServiceBlockingClient.syncExternalAccountTransactions(request.toGrpcRequest())
        require(response.success) { response.message }
        return ExternalTransactionSyncResult(syncedCount = response.syncedCount)
    }

    /**
     * 애플리케이션 종료 시 gRPC 채널을 닫아 네트워크 리소스를 정리합니다.
     */
    override fun destroy() {
        channel.shutdown()
    }

    /**
     * Asset 내부 동기화 요청 DTO를 Banking gRPC 계약 메시지로 변환합니다.
     */
    private fun ExternalTransactionSyncRequest.toGrpcRequest(): SyncExternalAccountTransactionsRequest {
        return SyncExternalAccountTransactionsRequest.newBuilder()
            .setUserId(userId)
            .setAssetAccountId(assetAccountId)
            .setAccountToken(accountToken)
            .setExternalAccountNumber(externalAccountNumber)
            .setCi(ci)
            .setUserName(userName)
            .setPhoneNumber(phoneNumber)
            .setCursor(cursor.toString())
            .build()
    }
}
