FROM maven:3.9-eclipse-temurin-21 AS build
ENV MAVEN_OPTS="-Xmx512m"
WORKDIR /workspace
COPY pom.xml ./
COPY framework/pom.xml framework/pom.xml
COPY service/pom.xml service/pom.xml
COPY api-core/pom.xml api-core/pom.xml
COPY framework/src framework/src
COPY service/src service/src
COPY api-core/src api-core/src
RUN mvn -B -q -pl api-core -am package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 lexflow && useradd --uid 10001 --gid lexflow --no-create-home lexflow \
    && mkdir -p /app/audit && chown -R lexflow:lexflow /app
WORKDIR /app
COPY --from=build --chown=lexflow:lexflow /workspace/api-core/target/api-core-0.1.0-SNAPSHOT.jar /app/api-core.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/api-core.jar"]
