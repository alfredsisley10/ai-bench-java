FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY bench-harness/ bench-harness/
COPY bench-cli/ bench-cli/

WORKDIR /workspace/bench-cli
RUN ./gradlew installDist --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd -r bench && useradd --no-log-init -r -g bench bench && \
    apt-get update && apt-get install -y --no-install-recommends git && \
    rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/bench-cli/build/install/bench-cli/ .

ENV AI_BENCH_ENV=local \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

USER bench
ENTRYPOINT ["./bin/bench-cli"]
