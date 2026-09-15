# Build stage: compile the service and collect its runtime libraries (Jackson only, no test libraries).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -q package -DskipTests \
    && mvn -B -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib

# Run stage: a JRE, the service jar and its libraries.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/loan-platform-qa-lab-1.0-SNAPSHOT.jar app.jar
COPY --from=build /build/target/lib lib
RUN useradd --system --no-create-home app
USER app

# Render passes PORT (10000 by default) and needs the server on 0.0.0.0.
ENV BIND_HOST=0.0.0.0 PORT=8080
EXPOSE 8080
CMD ["java", "-cp", "app.jar:lib/*", "lab.loans.LoanPlatformApp"]
