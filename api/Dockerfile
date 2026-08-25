# 兩段式：build 段要 JDK 與 Maven wrapper，runtime 段只留 JRE 與 jar。
# Fly 的 remote builder 直接吃這支，不需要本機推 registry。
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# 測試在本機／CI 跑，映像只負責打包：Testcontainers 要 Docker socket，
# 而 migration 在 ../supabase 也不在建置 context 裡。
# 在 build 段就把 glob 收斂成固定檔名：BuildKit **不會**執行「多個來源檔的 COPY
# 目的地必須是目錄」那條規則（legacy builder 會擋，BuildKit 靜靜挑字典序最後一個），
# 所以 target/ 哪天多出第二個 hapeetrail-*.jar 就是一顆無聲的錯 jar。
# 交給 mv：多於一個檔它會以「目的地不是目錄」失敗，響亮地紅在建置階段。
# （`.jar.original` 結尾不是 `.jar`，本來就不在 glob 內。）
RUN ./mvnw -B -ntp -DskipTests package && mv target/hapeetrail-*.jar /app.jar

FROM eclipse-temurin:21-jre
# ponytail: 沒做 layered jar 拆層——單機單服務，冷啟差幾秒不值得多一份設定。
COPY --from=build /app.jar /app.jar
RUN useradd --system --uid 1001 app
USER 1001
EXPOSE 8080
# 1GB 機器：堆上限交給容器記憶體的百分比，不寫死 -Xmx（換機型不用改 Dockerfile）。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app.jar"]
