# syntax=docker/dockerfile:1.7
# Anveshan API image (TECHSPEC §16.4). Multi-stage: compile in a JDK image, run in a JRE image.
# Tests are NOT run here — `./mvnw verify` and CI do that.

FROM eclipse-temurin:21.0.12_8-jdk-noble AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q package -DskipTests

FROM eclipse-temurin:21.0.12_8-jre-noble
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --create-home anveshan
WORKDIR /app
COPY --from=build /src/target/anveshan-api.jar app.jar
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
