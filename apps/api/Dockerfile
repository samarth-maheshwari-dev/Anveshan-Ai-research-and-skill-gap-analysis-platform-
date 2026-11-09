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

# Embedding model (intfloat/multilingual-e5-small, MIT), pinned revision, checksum-verified.
# Baked into the image so nothing is downloaded at runtime. Keep in sync with scripts/fetch-model.sh.
FROM scratch AS model
ARG E5_BASE=https://huggingface.co/intfloat/multilingual-e5-small/resolve/614241f622f53c4eeff9890bdc4f31cfecc418b3/onnx
ADD --chmod=644 --checksum=sha256:ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665 ${E5_BASE}/model.onnx /model.onnx
ADD --chmod=644 --checksum=sha256:0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39 ${E5_BASE}/tokenizer.json /tokenizer.json

FROM eclipse-temurin:21.0.12_8-jre-noble
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --create-home anveshan
WORKDIR /app
COPY --from=model / /app/models/multilingual-e5-small/
COPY --from=build /src/target/anveshan-api.jar app.jar
USER 10001
# Heap 50%: the other half is native memory for the ONNX model (~470 MB) and thread stacks.
ENV ANV_EMBEDDINGS_MODEL_DIR=/app/models/multilingual-e5-small \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
