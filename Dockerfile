# Etapa de build: gera o JAR executável com o Maven Wrapper do projeto.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Só o necessário para o build é copiado; .env, target/ e arquivos de IDE ficam fora da imagem.
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src

# Os testes dependem de um PostgreSQL acessível, que não existe durante o build da imagem.
RUN sh mvnw -B -DskipTests package

# Etapa final: apenas o JRE e o JAR.
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN useradd --system --no-create-home appuser
USER appuser

COPY --from=build /workspace/target/*.jar app.jar

# A porta vem de PORT (server.port=${PORT:8080}). Credenciais e demais configurações vêm só de variáveis de ambiente.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
