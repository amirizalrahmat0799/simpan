# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home /data simpan && mkdir -p /data && chown simpan /data
COPY --from=build /src/target/simpan.jar /app/simpan.jar
USER simpan
ENV SIMPAN_DATA_DIR=/data
VOLUME /data
# 9000: S3 API, 9001: health and Prometheus metrics
EXPOSE 9000 9001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/simpan.jar"]
