# Moaje Asset Domain

원장, 거래 내역, 이벤트 아웃박스, Daily 가용 생활비 스냅샷을 담당하는 Asset 도메인 Spring Boot 프로젝트입니다.

Banking 도메인과의 동기 통신(gRPC) 및 비동기 이벤트 처리는 현재 Facade/Handler 골격만 포함하고, 실제 proto 및 Kafka 연동은 추후 연결합니다.
