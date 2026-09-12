package com.moaje.asset.account.integration.banking

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

class BankingGrpcClientTlsProperties {
    var enabled: Boolean = true
    var certificateChainPath: String = ""
    var privateKeyPath: String = ""
    var trustCertificateCollectionPath: String = ""
    var overrideAuthority: String = ""
}

@Component
@ConfigurationProperties(prefix = "moaje.asset.banking")
class BankingGrpcClientProperties {
    var grpcHost: String = "localhost"
    var grpcPort: Int = 9091
    var projectionReconciliationTimeoutMs: Long = 5_000
    var tls: BankingGrpcClientTlsProperties = BankingGrpcClientTlsProperties()
}
