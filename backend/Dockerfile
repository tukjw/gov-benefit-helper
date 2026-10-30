# 운영 이미지(결정 0036). jar는 이미지 워크플로(.github/workflows/image.yml)가 ./gradlew bootJar로 먼저 만든다.
FROM eclipse-temurin:21-jre-noble

RUN useradd --system --no-create-home app
WORKDIR /app
COPY build/libs/app.jar app.jar
USER app

# 컨테이너의 기본 시간대는 UTC다. Hibernate가 JVM 기본 시간대로 시각을 변환하므로 Asia/Seoul로 맞춘다(결정 0002).
# 힙은 컨테이너 메모리 한도의 75%까지 쓴다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Seoul"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
