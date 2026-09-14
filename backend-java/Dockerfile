FROM maven:3.9.9-eclipse-temurin-23 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -DskipTests package

FROM eclipse-temurin:23-jre
WORKDIR /app
ENV JAVA_OPTS=""
COPY --from=build /workspace/target/presale-agent-java-0.1.0.jar /app/presale-agent-java.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/presale-agent-java.jar"]
