# =============================================================================
# Zevra AI - Multi-Stage Docker Build
# Stage 1: Build the Spring Boot fat JAR with Maven
# Stage 2: Minimal JRE runtime image
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1: Builder
# ---------------------------------------------------------------------------
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy dependency descriptor first for layer caching
COPY pom.xml .

# Download all dependencies (cached unless pom.xml changes)
RUN mvn dependency:go-offline --batch-mode --quiet

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests --batch-mode --quiet

# ---------------------------------------------------------------------------
# Stage 2: Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

# Security: install curl for health checks, then clean up
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user and group
RUN groupadd --system --gid 1001 nexus \
    && useradd --system --uid 1001 --gid nexus --no-create-home nexus

# Create directories with correct ownership
RUN mkdir -p /app /data/documents \
    && chown -R nexus:nexus /app /data/documents

WORKDIR /app

# Copy the fat JAR from builder stage
COPY --from=builder --chown=nexus:nexus /build/target/*.jar app.jar

# Switch to non-root user
USER nexus

# Render injects PORT at runtime; default 8080 for local Docker runs
EXPOSE 8080

# Health check — port resolved at runtime via shell
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD curl -sf http://localhost:${PORT:-8080}/api/v1/actuator/health | grep -q '"status":"UP"' || exit 1

# JVM tuned for Render free tier (512 MB RAM); PORT passed via env
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=70.0", \
    "-XX:+UseSerialGC", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
