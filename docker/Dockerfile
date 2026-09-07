# 멀티스테이지. 빌드 도구가 런타임 이미지에 남지 않는다.
#
# 모듈을 인자로 받는다. app 과 mock-supplier 가 같은 빌드 절차를 쓰므로
# Dockerfile 을 두 벌 유지할 이유가 없다.
ARG MODULE=app

# ── build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

# wrapper 와 빌드 스크립트를 먼저 넣어 의존성 해석 결과를 레이어로 남긴다.
# 소스만 바뀌었을 때 의존성을 다시 받지 않는다.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY app/build.gradle.kts app/
COPY application/build.gradle.kts application/
COPY domain/build.gradle.kts domain/
COPY storage/build.gradle.kts storage/
COPY supplier-client/build.gradle.kts supplier-client/
COPY mock-supplier/build.gradle.kts mock-supplier/
RUN ./gradlew --no-daemon dependencies --quiet > /dev/null 2>&1 || true

COPY . .
ARG MODULE
# 테스트는 여기서 돌리지 않는다. Testcontainers 가 Docker 를 필요로 하는데
# 이미지 빌드 중에는 Docker 데몬이 없다. 검증은 ./gradlew check 로 별도로 한다.
RUN ./gradlew --no-daemon :${MODULE}:bootJar -x test

# ── runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
ARG MODULE

# root 로 돌리지 않는다. 컨테이너가 뚫렸을 때 얻는 권한을 줄인다.
RUN addgroup -S stay && adduser -S stay -G stay
USER stay
WORKDIR /app

COPY --from=builder --chown=stay:stay /build/${MODULE}/build/libs/*.jar app.jar

# 컨테이너 CPU/메모리 제한을 JVM 이 인식하게 한다. 이것이 없으면 힙 상한을
# 호스트 전체 메모리 기준으로 잡아 cgroup 한계를 넘고 OOMKilled 된다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
