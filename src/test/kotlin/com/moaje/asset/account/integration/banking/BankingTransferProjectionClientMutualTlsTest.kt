package com.moaje.asset.account.integration.banking

import com.moaje.grpc.banking.BankingServiceGrpc
import com.moaje.grpc.banking.GetAccountProjectionSnapshotRequest
import com.moaje.grpc.banking.GetAccountProjectionSnapshotResponse
import com.moaje.common.Money
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth
import io.grpc.netty.shaded.io.netty.handler.ssl.util.SelfSignedCertificate
import io.grpc.stub.StreamObserver
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("Asset에서 Banking으로 연결하는 mTLS Client")
class BankingTransferProjectionClientMutualTlsTest {
    @Test
    @DisplayName("mTLS가 활성화됐는데 인증서 경로가 없으면 plaintext Banking 호출을 만들지 않는다")
    fun failsClosedWhenTlsFilesAreMissing() {
        // given: 운영 기본값인 TLS enabled 상태지만 Asset과 Banking 인증서 경로가 비어 있다.
        val client = BankingTransferProjectionClient(BankingGrpcClientProperties().apply { grpcPort = 1 })

        try {
            // when & then: 네트워크 연결 전에 설정 오류로 실패해 비인증 채널로 우회하지 않는다.
            assertThatThrownBy { client.getAccountSnapshot(101L, Instant.EPOCH) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("trust certificates")
        } finally {
            client.destroy()
        }
    }

    @Test
    @DisplayName("Banking 서버를 검증하고 Asset client certificate를 제시해 Snapshot을 조회한다")
    fun callsBankingSnapshotWithMutualTls() {
        // given
        val bankingCertificate = SelfSignedCertificate("banking.test")
        val assetCertificate = SelfSignedCertificate("asset.test")
        val service = object : BankingServiceGrpc.BankingServiceImplBase() {
            override fun getAccountProjectionSnapshot(
                request: GetAccountProjectionSnapshotRequest,
                responseObserver: StreamObserver<GetAccountProjectionSnapshotResponse>,
            ) {
                responseObserver.onNext(
                    GetAccountProjectionSnapshotResponse.newBuilder()
                        .setAccountId(request.accountId)
                        .setPrincipalId("user-1")
                        .setBalance(Money.newBuilder().setAmount(12_000).setCurrency("KRW"))
                        .setAccountStatus("ACTIVE")
                        .setAsOfEpochMillis(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli())
                        .build(),
                )
                responseObserver.onCompleted()
            }
        }
        val server = NettyServerBuilder.forPort(0)
            .sslContext(
                GrpcSslContexts.forServer(bankingCertificate.certificate(), bankingCertificate.privateKey())
                    .trustManager(assetCertificate.certificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build(),
            )
            .addService(service)
            .build()
            .start()
        val properties = BankingGrpcClientProperties().apply {
            grpcHost = "localhost"
            grpcPort = server.port
            tls.certificateChainPath = assetCertificate.certificate().absolutePath
            tls.privateKeyPath = assetCertificate.privateKey().absolutePath
            tls.trustCertificateCollectionPath = bankingCertificate.certificate().absolutePath
            tls.overrideAuthority = "banking.test"
        }
        val client = BankingTransferProjectionClient(properties)

        try {
            // when
            val snapshot = client.getAccountSnapshot(101L, Instant.EPOCH)

            // then
            assertThat(snapshot.accountId).isEqualTo(101L)
            assertThat(snapshot.balance).isEqualByComparingTo("12000")
        } finally {
            client.destroy()
            server.shutdownNow()
            bankingCertificate.delete()
            assetCertificate.delete()
        }
    }
}
