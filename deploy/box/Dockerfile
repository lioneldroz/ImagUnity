# Logiciel ImagUnity de la box. Contexte de build : ../backend
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN sh ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/app/target/imagunity-box.jar /app/imagunity-box.jar
VOLUME /data
# 8080 : console et API ; 11112 : entrée DICOM (phase 2)
EXPOSE 8080 11112
ENTRYPOINT ["java", "-jar", "/app/imagunity-box.jar"]
