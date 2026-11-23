# Stage 1: build with the Maven wrapper (cached dependency layer first)
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package && cp target/pg-control-plane-*.jar /app.jar

# Stage 2: small runtime image, non-root
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 pgcp
USER 10001
COPY --from=build /app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
