# SmartLib — Spring Boot server with the HTML/CSS/JS frontend.
# Build from the repository root (the build needs ../frontend):
#   docker build -f java-backend/Dockerfile -t smartlib .
#   docker run -p 3000:3000 --env-file .env smartlib
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY frontend frontend
COPY java-backend java-backend
WORKDIR /src/java-backend
RUN chmod +x mvnw && ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/java-backend/target/smartlib.jar smartlib.jar
ENV TZ=UTC PORT=3000
EXPOSE 3000
CMD ["java", "-XX:MaxRAMPercentage=75", "-jar", "smartlib.jar"]
