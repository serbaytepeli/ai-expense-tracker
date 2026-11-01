# syntax=docker/dockerfile:1
#
# Runtime image built from the JAR that CI has already built and tested
# (docs/architecture.md section 6.2). There are no build tools in the image.
#
#   cd backend && ./gradlew build
#   docker build --build-arg JAR_FILE=build/libs/ai-expense-tracker-backend-0.0.1-SNAPSHOT.jar \
#     -t aiexpense-backend:local .
#
# Required at run time: DB_URL, DB_USERNAME, DB_PASSWORD, JWT_SECRET (see .env.example).

# Eclipse Temurin 21 JRE on Ubuntu 24.04, pinned by digest (updated by Dependabot from T0.5).
ARG BASE_IMAGE=eclipse-temurin:21-jre-noble@sha256:0eda5830ed7b669abc246a8dc520ea8a23cab06f9a8d4b6964109e2f2394e46f

# --- Stage 1: split the Spring Boot JAR into layers (better image layer caching) ---
FROM ${BASE_IMAGE} AS extract
WORKDIR /extract
# Path of the tested executable JAR, relative to backend/. Required; there is no default
# so that an untested or wrong JAR (for example the "-plain" JAR) is never picked silently.
ARG JAR_FILE
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# --- Stage 2: runtime ---
FROM ${BASE_IMAGE}
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=extract /extract/extracted/dependencies/ ./
COPY --from=extract /extract/extracted/spring-boot-loader/ ./
COPY --from=extract /extract/extracted/snapshot-dependencies/ ./
COPY --from=extract /extract/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "application.jar"]
