package com.moaje.asset.api.grpc

import io.grpc.BindableService
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.io.File

class AssetGrpcServerTlsProperties {
    var enabled: Boolean = true
    var certificateChainPath: String = ""
    var privateKeyPath: String = ""
    var trustCertificateCollectionPath: String = ""
}

@Component
@ConfigurationProperties(prefix = "moaje.asset.grpc.server")
class AssetGrpcServerProperties {
    var port: Int = 9090
    var tls: AssetGrpcServerTlsProperties = AssetGrpcServerTlsProperties()
}

@Component
class AssetGrpcServer(
    private val properties: AssetGrpcServerProperties,
    private val services: List<BindableService>,
) : SmartLifecycle {
    private var server: Server? = null
    private var running: Boolean = false

    /**
     * Asset gRPC도 인증서를 제시한 신뢰 클라이언트만 받도록 clientAuth=REQUIRE를 적용한다.
     * 현재 Banking의 역방향 호출은 제거했지만, 남은 DailyCashflow RPC가 무인증 내부 포트로 노출되지 않게 같은 원칙을 유지한다.
     */
    override fun start() {
        if (running) return

        val builder = NettyServerBuilder.forPort(properties.port)
        if (properties.tls.enabled) {
            val tls = properties.tls
            val certificateChain = tls.certificateChainPath.requireReadableFile("Asset server certificate chain")
            val privateKey = tls.privateKeyPath.requireReadableFile("Asset server private key")
            val trustCertificates = tls.trustCertificateCollectionPath.requireReadableFile("Asset client trust certificates")
            builder.sslContext(
                GrpcSslContexts.forServer(certificateChain, privateKey)
                    .trustManager(trustCertificates)
                    .clientAuth(ClientAuth.REQUIRE)
                    .build(),
            )
        }
        services.forEach(builder::addService)
        server = builder.build().start()
        running = true
    }

    override fun stop() {
        server?.shutdown()
        running = false
    }

    override fun isRunning(): Boolean = running

    private fun String.requireReadableFile(description: String): File {
        require(isNotBlank()) { "$description 경로는 필수입니다." }
        return File(this).also { file ->
            require(file.isFile && file.canRead()) { "$description 파일을 읽을 수 없습니다. path=${file.path}" }
        }
    }
}
