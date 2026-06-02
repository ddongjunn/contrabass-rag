# syntax=docker/dockerfile:1

# --- build stage: Gradle(JDK 21) → bootJar ---
FROM gradle:8-jdk21 AS build
WORKDIR /app
# 의존성 캐시 레이어 (소스보다 먼저 빌드 스크립트만 복사)
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# 베이스 이미지(gradle:8-jdk21)에 설치된 gradle 사용 — 래퍼의 services.gradle.org 다운로드 회피(제한된 egress 환경)
RUN gradle dependencies --no-daemon > /dev/null 2>&1 || true
COPY src ./src
RUN gradle bootJar --no-daemon

# --- runtime stage: JRE 21 + jar ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# bootJar 하나만 산출됨(build.gradle.kts에서 plain jar 비활성) → *.jar 안전
COPY --from=build /app/build/libs/*.jar app.jar
# non-root 실행 (전용 시스템 유저)
RUN useradd -r -u 1001 appuser && chown appuser:appuser /app/app.jar
USER appuser
EXPOSE 8080
# 시크릿/설정은 환경변수로만 주입(이미지에 굽지 않음).
# -XX:MaxRAMPercentage: 컨테이너 메모리 한도를 존중해 힙 산정(OOM 방지).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
