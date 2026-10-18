# Spring Boot backend. Configuration files and the single label-free feature file of the served
# split are mounted read-only at run time; labels and other splits are not available.
FROM eclipse-temurin:25.0.4.1_1-jdk-noble AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sh mvnw -B -ntp -q dependency:go-offline
COPY src/ src/
# Tests run outside the image build (they need Docker for Testcontainers).
RUN sh mvnw -B -ntp -q -DskipTests package

FROM eclipse-temurin:25.0.4.1_1-jre-noble
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /build/target/backend-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
