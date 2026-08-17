# syntax=docker/dockerfile:1
#
# The plugin-velocity-jar chart copies this data-only image's final shaded
# Velocity plugin from /jar/plugin.jar. The shared Docker release workflow
# builds linux/amd64 and supplies GitHub Packages credentials as a BuildKit
# secret; no credential is retained in an image layer.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

ARG GITHUB_USER

COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts version.txt ./
COPY velocity/ velocity/

RUN --mount=type=secret,id=github_token,required=true \
    /bin/sh -euc '\
      : "${GITHUB_USER:?GITHUB_USER build arg is required}"; \
      token="$(cat /run/secrets/github_token)"; \
      ./gradlew --no-daemon :velocity:shadowJar \
        -Pgithub.user="${GITHUB_USER}" \
        -Pgithub.token="${token}" \
    '

RUN mkdir -p /out && \
    test "$(find /src/velocity/build/libs -maxdepth 1 -type f -name '*.jar' | wc -l)" -eq 1 && \
    cp "$(find /src/velocity/build/libs -maxdepth 1 -type f -name '*.jar' -print -quit)" /out/plugin.jar

FROM alpine:3
RUN mkdir -p /jar
COPY --from=build /out/plugin.jar /jar/plugin.jar
# No ENTRYPOINT or CMD: plugin-velocity-jar copies the JAR from this image.
