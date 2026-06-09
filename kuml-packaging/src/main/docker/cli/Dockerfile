# ── Stage 1: fat JAR source ───────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
# The shadow JAR is copied in by the CI workflow; for local builds run:
#   ./gradlew :kuml-cli:shadowJar
#   docker build \
#     --build-arg JAR_FILE=kuml-cli-<version>-all.jar \
#     --file kuml-packaging/src/main/docker/cli/Dockerfile \
#     --tag ghcr.io/kuml-dev/kuml-cli:latest \
#     kuml-cli/build/libs
ARG JAR_FILE=kuml-cli-all.jar
COPY ${JAR_FILE} app.jar

# ── Stage 2: slim runtime image ───────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /opt/kuml
COPY --from=builder /build/app.jar kuml-cli.jar

LABEL org.opencontainers.image.title="kUML CLI" \
      org.opencontainers.image.description="Kotlin-native UML/SysML2/C4 modelling DSL" \
      org.opencontainers.image.source="https://github.com/kuml-dev/kUML" \
      org.opencontainers.image.url="https://kuml.dev"

ENTRYPOINT ["java", "-jar", "/opt/kuml/kuml-cli.jar"]
CMD ["--help"]
