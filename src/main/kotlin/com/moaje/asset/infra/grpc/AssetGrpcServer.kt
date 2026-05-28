package com.moaje.asset.infra.grpc

import io.grpc.BindableService
import io.grpc.Server
import io.grpc.ServerBuilder
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "moaje.asset.grpc.server")
class AssetGrpcServerProperties {
    var port: Int = 9090
}

@Component
class AssetGrpcServer(
    private val properties: AssetGrpcServerProperties,
    private val services: List<BindableService>,
) : SmartLifecycle {
    private var server: Server? = null
    private var running: Boolean = false

    /**
     * Spring 컨텍스트가 시작될 때 Asset gRPC 서버를 지정된 포트로 기동합니다.
     */
    override fun start() {
        if (running) {
            return
        }

        val builder = ServerBuilder.forPort(properties.port)
        services.forEach { builder.addService(it) }
        server = builder.build().start()
        running = true
    }

    /**
     * Spring 컨텍스트가 종료될 때 Asset gRPC 서버를 안전하게 종료합니다.
     */
    override fun stop() {
        server?.shutdown()
        running = false
    }

    /**
     * 현재 Asset gRPC 서버의 실행 여부를 Spring lifecycle에 알려줍니다.
     */
    override fun isRunning(): Boolean = running
}
