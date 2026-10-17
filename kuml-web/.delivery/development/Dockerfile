FROM mcr.microsoft.com/playwright:v1.63.0-noble

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl openjdk-21-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

COPY scripts/ /opt/kuml/scripts/
RUN chmod +x /opt/kuml/scripts/test-browser.sh

WORKDIR /workspace
