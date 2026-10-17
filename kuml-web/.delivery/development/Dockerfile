# Keep the image tag in lockstep with "@playwright/test" in kuml-web/package.json: the browsers baked
# into this image are exactly the ones that Playwright version expects, and a different npm version
# cannot find them. PlaywrightVersionSyncTest (kuml-web) fails the build when they drift apart.
FROM mcr.microsoft.com/playwright:v1.64.0-noble

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl openjdk-21-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

COPY scripts/ /opt/kuml/scripts/
RUN chmod +x /opt/kuml/scripts/test-browser.sh

WORKDIR /workspace
