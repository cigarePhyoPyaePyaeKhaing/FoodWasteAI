# =====================================================================
# FoodWaste AI - Multi-Stage Dockerfile for Railway / Container Deployment
# Includes OpenJDK 17 + SWI-Prolog Engine
# =====================================================================

# ---------------------------------------------------------------------
# STAGE 1: Build Java Application with Maven
# ---------------------------------------------------------------------
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

# Cache Maven dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy project source and build fat JAR
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---------------------------------------------------------------------
# STAGE 2: Lightweight Production Runtime with SWI-Prolog
# ---------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy

# Install SWI-Prolog and minimal utilities
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    swi-prolog \
    ca-certificates \
    curl && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy executable Fat JAR from builder stage
COPY --from=builder /app/target/foodwaste-ai.jar /app/foodwaste-ai.jar
COPY src/main/resources/prolog /app/src/main/resources/prolog
COPY src/main/webapp /app/src/main/webapp

# Default port for Railway (Railway automatically provides $PORT at runtime)
ENV PORT=8080
ENV SWIPL_PATH=swipl
ENV APP_ENV=production

EXPOSE 8080

# Launch application
CMD ["sh", "-c", "java -Dserver.port=${PORT:-8080} -jar /app/foodwaste-ai.jar"]
