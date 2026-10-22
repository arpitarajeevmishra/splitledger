# Build and runtime are separate stages so the shipped image carries a JRE and a jar, not
# a JDK, Maven, and the whole dependency cache. The difference is roughly 600MB of
# attack surface that never needed to leave the builder.

FROM eclipse-temurin:27-jdk-alpine AS build
WORKDIR /build

# Dependencies first: this layer is cached unless the pom changes, so an ordinary code
# change does not re-download the world on every build.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests ran in CI before this image is built. Running them again here would double the
# pipeline's slowest step to prove something already proven.
RUN ./mvnw -B -q -DskipTests package && mv target/*.jar app.jar

FROM eclipse-temurin:27-jre-alpine AS runtime
WORKDIR /app

# A non-root user, because a process that never needs to write to its own image should
# not be able to.
RUN addgroup -S app && adduser -S -G app app
COPY --from=build --chown=app:app /build/app.jar app.jar
USER app

EXPOSE 8080

# Container-aware by default on modern JVMs; stated anyway so a change in the host's
# memory limits is visible here rather than surprising.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# The health endpoint is public, needs no database, and is the same one the deploy smoke
# test uses -- so "healthy" means the same thing to Docker and to the pipeline.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/v1/health-check || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
