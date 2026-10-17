FROM mcr.microsoft.com/playwright:v1.63.0-noble AS build

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-21-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
COPY . .

RUN chmod +x gradlew \
    && ./gradlew :kuml-cli:installDist --no-daemon

FROM eclipse-temurin:21-jre-noble

WORKDIR /opt/kuml
COPY --from=build /workspace/kuml-cli/build/install/kuml/ ./

EXPOSE 8080

ENTRYPOINT ["/opt/kuml/bin/kuml", "serve", "--host", "0.0.0.0", "--port", "8080"]
