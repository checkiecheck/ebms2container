# =============================================================================
# cpa-service – Multi-stage Dockerfile
# Build context: project root (ebms-adapter/)
# Gebruik: docker build -f cpa-service/Dockerfile -t cpa-service .
# =============================================================================

# ── Stage 1: Maven build ──────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

# Kopieer Maven-configuratie (.mvn/jvm.config, maven.config, wrapper)
COPY .mvn                         .mvn

COPY pom.xml                      pom.xml
COPY ebms-common/pom.xml          ebms-common/pom.xml
COPY ebms-orchestrator/pom.xml    ebms-orchestrator/pom.xml
COPY cpa-service/pom.xml          cpa-service/pom.xml
COPY crypto-service/pom.xml       crypto-service/pom.xml

RUN mvn -pl cpa-service -am \
        dependency:go-offline \
        -q

COPY ebms-common/src              ebms-common/src
COPY cpa-service/src              cpa-service/src

RUN mvn -pl cpa-service -am \
        package -DskipTests

# ── Stage 2: JRE runtime ──────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S ebms && adduser -S ebms -G ebms

COPY --from=build /workspace/cpa-service/target/*.jar app.jar
RUN chown ebms:ebms app.jar

USER ebms
EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8081/actuator/health/liveness || exit 1

ENTRYPOINT ["java",                               \
  "-XX:+UseContainerSupport",                     \
  "-XX:MaxRAMPercentage=75.0",                    \
  "-Djava.security.egd=file:/dev/./urandom",      \
  "-jar", "/app/app.jar"]
