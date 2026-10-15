# 构建阶段：用 Maven 镜像编译打包（依赖层单独缓存，改代码时不必重新下载依赖）
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# 运行阶段：只带 JRE，镜像更小
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/offermate-server-1.0.0.jar app.jar
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"
EXPOSE 18080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
