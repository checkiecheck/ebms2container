# =============================================================================
# crypto-service – Multi-stage Dockerfile
# Build context: project root (ebms-adapter/)
# Gebruik: docker build -f crypto-service/Dockerfile -t crypto-service .
# =============================================================================

# ── Stage 1: Maven build ──────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

COPY pom.xml                      pom.xml
COPY ebms-common/pom.xml          ebms-common/pom.xml
COPY ebms-orchestrator/pom.xml    ebms-orchestrator/pom.xml
COPY cpa-service/pom.xml          cpa-service/pom.xml
COPY crypto-service/pom.xml       crypto-service/pom.xml

RUN mvn -pl crypto-service -am \
        dependency:go-offline \
        -q --no-transfer-progress

COPY ebms-common/src              ebms-common/src
COPY crypto-service/src           crypto-service/src

RUN mvn -pl crypto-service -am \
        package -DskipTests \
        --no-transfer-progress

# ── Stage 2: JRE runtime ──────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S ebms && adduser -S ebms -G ebms

# Maak een map aan voor de KeyStore (wordt als volume gemount)
RUN mkdir -p /app/keystores && chown ebms:ebms /app/keystores

COPY --from=build /workspace/crypto-service/target/*.jar app.jar
RUN chown ebms:ebms app.jar

USER ebms
EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8082/actuator/health/liveness || exit 1

ENTRYPOINT ["java",                                  \
  "-XX:+UseContainerSupport",                        \
  "-XX:MaxRAMPercentage=75.0",                       \
  "-Djava.security.egd=file:/dev/./urandom",         \
  "-Dorg.apache.xml.security.ignoreLineBreaks=true", \
  "-jar", "/app/app.jar"]
