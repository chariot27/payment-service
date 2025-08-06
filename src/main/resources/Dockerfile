# Etapa 1: Build com Gradle
FROM gradle:8.7-jdk17 AS builder
WORKDIR /app

# Copia todos os arquivos para o container builder
COPY . .

# Executa o build sem testes
RUN gradle build -x test --no-daemon

# Etapa 2: Imagem final com JRE otimizado
FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app

# Copia o .jar da etapa anterior
COPY --from=builder /app/build/libs/*.jar app.jar

# Expõe a porta do serviço
EXPOSE 8084

# Comando de execução do microserviço
ENTRYPOINT ["java", "-jar", "app.jar"]
