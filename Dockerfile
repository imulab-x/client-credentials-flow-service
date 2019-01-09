FROM openjdk:8-jdk-alpine

COPY ./build/libs/client-credentials-flow-service-*.jar client-credentials-flow-service.jar

ENTRYPOINT ["java", "-jar", "/client-credentials-flow-service.jar"]