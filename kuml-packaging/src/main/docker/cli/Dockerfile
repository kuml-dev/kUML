# ── Stage 1: fat JAR source ───────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
# The shadow JAR and its sibling style/ directory (the :kuml-style-worker
# runtime jars `kuml validate`'s style-check child JVM needs — see
# kuml-cli/build.gradle.kts's copyStyleWorkerLibForShadowJar task and
# StyleWorkerLibLocator's KDoc for the full resolution chain) are copied in
# by the CI workflow; for local builds run:
#   ./gradlew :kuml-cli:shadowJar :kuml-cli:copyStyleWorkerLibForShadowJar
#   docker build \
#     --build-arg JAR_FILE=kuml-cli-<version>-all.jar \
#     --file kuml-packaging/src/main/docker/cli/Dockerfile \
#     --tag ghcr.io/kuml-dev/kuml-cli:latest \
#     kuml-cli/build/libs
ARG JAR_FILE=kuml-cli-all.jar
COPY ${JAR_FILE} app.jar
COPY style/ style/

# ── Stage 2: slim runtime image ───────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /opt/kuml
COPY --from=builder /build/app.jar kuml-cli.jar
# Sibling style/ directory, required by StyleWorkerLibLocator's
# "<dir of the jar containing NamedArgumentStyleCheck>/style" resolution rule
# — without it, `kuml validate` inside this image silently degrades to a
# STYLE_CHECK_UNAVAILABLE warning instead of actually running the check.
COPY --from=builder /build/style/ style/

LABEL org.opencontainers.image.title="kUML CLI" \
      org.opencontainers.image.description="Kotlin-native UML/SysML2/C4 modelling DSL" \
      org.opencontainers.image.source="https://github.com/kuml-dev/kUML" \
      org.opencontainers.image.url="https://kuml.dev"

ENTRYPOINT ["java", "-jar", "/opt/kuml/kuml-cli.jar"]
CMD ["--help"]
