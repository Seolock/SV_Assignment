FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
COPY --from=build /src/build/libs/*.jar /app.jar
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app.jar"]
