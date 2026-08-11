# =============================================================================
# ebms-orchestrator – Multi-stage Dockerfile
# Build context: project root (ebms-adapter/)
# Gebruik: docker build -f ebms-orchestrator/Dockerfile -t ebms-orchestrator .
# =============================================================================

# ── Stage 1: Maven build ──────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

# Kopieer alle POM-bestanden voor dependency-cache (Docker layer caching)
COPY pom.xml                      pom.xml
COPY ebms-common/pom.xml          ebms-common/pom.xml
COPY ebms-orchestrator/pom.xml    ebms-orchestrator/pom.xml
COPY cpa-service/pom.xml          cpa-service/pom.xml
COPY crypto-service/pom.xml       crypto-service/pom.xml

# Warm de Maven dependency-cache op (wordt gecached zolang POM's ongewijzigd zijn)
RUN mvn -pl ebms-orchestrator -am \
        dependency:go-offline \
        -q --no-transfer-progress

# Kopieer broncode en bouw de module (plus transitieve afhankelijkheden)
COPY ebms-common/src              ebms-common/src
COPY ebms-orchestrator/src        ebms-orchestrator/src

RUN mvn -pl ebms-orchestrator -am \
        package -DskipTests \
        --no-transfer-progress

# ── Stage 2: JRE runtime ──────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Maak een dedicated non-root gebruiker (beveiligingseis)
RUN addgroup -S ebms && adduser -S ebms -G ebms

# Kopieer het gebouwde JAR-bestand
COPY --from=build /workspace/ebms-orchestrator/target/*.jar app.jar
RUN chown ebms:ebms app.jar

USER ebms
EXPOSE 8080

# Health check via Spring Boot Actuator
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java",                               \
  "-XX:+UseContainerSupport",                     \
  "-XX:MaxRAMPercentage=75.0",                    \
  "-Djava.security.egd=file:/dev/./urandom",      \
  "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:prod}", \
  "-jar", "/app/app.jar"]
