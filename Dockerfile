FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /workspace

ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx384m -Dorg.gradle.daemon=false"

COPY moaje-grpc-contracts ./moaje-grpc-contracts
COPY moaje-asset ./moaje-asset

WORKDIR /workspace/moaje-asset

RUN chmod +x ./gradlew
RUN ./gradlew clean bootJar -x test --no-daemon --max-workers=1

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=builder /workspace/moaje-asset/build/libs/*.jar app.jar

EXPOSE 8082
EXPOSE 9090

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
