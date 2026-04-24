FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY bench-harness/ bench-harness/
COPY bench-webui/ bench-webui/

WORKDIR /workspace/bench-webui
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd -r bench && useradd --no-log-init -r -g bench bench

COPY --from=build /workspace/bench-webui/build/libs/*.jar app.jar

ENV AI_BENCH_ENV=local \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8080

USER bench
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
