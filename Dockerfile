# ============================================================
# 微旅途后端 · 多阶段构建
# ------------------------------------------------------------
# 构建：docker build -t micro-trip-server:1.0.0 .
# 运行：docker run -d --name micro-trip-server --env-file .env \
#         -p 3000:3000 -v microtrip-data:/app/data micro-trip-server:1.0.0
#
# 设计要点：
#   1) 分阶段 —— 编译期要 JDK + Maven（约 700MB），运行期只要 JRE，
#      最终镜像显著瘦身，同时把源码与构建工具留在构建层。
#   2) 依赖层缓存 —— 先只拷 pom.xml 跑 go-offline，只要 pom 没变，
#      改业务代码不会触发重新下载全部依赖（构建从几分钟降到十几秒）。
#   3) 非 root 运行 —— 容器逃逸时攻击者拿到的只是普通用户。
#   4) sh -c + exec —— Java 进程成为 PID 1 并正确接收 SIGTERM，
#      配合 server.shutdown=graceful 实现优雅停机（否则在途的轨迹同步会被硬切断）。
#   5) 选 alpine 而非 jammy —— Temurin 的 ubuntu 基础镜像不含 curl/wget，
#      下面的 HEALTHCHECK 会静默失效；alpine 自带 busybox wget。
# ============================================================

# ---------------- Stage 1：构建 ----------------
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# 先解析依赖（独立层，便于缓存复用）
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

# 再拷源码编译打包（跳过测试：测试在本地/CI 跑，镜像构建只求快）
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------------- Stage 2：运行 ----------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# 时区：轨迹时间是业务语义的一部分，容器默认 UTC 会让日志与
# 「今日步数」这类按天统计错位 8 小时。
# 刻意保留 tzdata 不删除（约 3MB）：删掉它虽然能让 /etc/localtime 这份副本继续可用，
# 但任何依赖 /usr/share/zoneinfo 的工具（日志时间转换、运维排查脚本）都会失效，
# 换 3MB 镜像体积不划算。
ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata \
    && cp /usr/share/zoneinfo/$TZ /etc/localtime \
    && echo $TZ > /etc/timezone

# 非 root 用户（uid 固定，便于宿主机挂载卷时对齐权限）
RUN addgroup -g 1001 -S app && adduser -u 1001 -S -G app -h /app app

WORKDIR /app

# 可写目录：H2 文件库 / 日志
RUN mkdir -p /app/data /app/logs && chown -R app:app /app

COPY --from=builder --chown=app:app /build/target/micro-trip-server-boot.jar /app/app.jar

USER app

# 容器内默认值（可被 --env-file / -e 覆盖）
ENV LOG_FILE=/app/logs/app.log \
    SPRING_PROFILES_ACTIVE=prod,mysql,redis \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# 用 sh -c 是为了让 JAVA_OPTS 这个字符串被展开成多个参数；
# exec 保证 java 替换掉 shell 成为 PID 1。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

EXPOSE 3000

# 探活用 liveness 而非 /health：前者不探测数据库依赖，
# 「进程活着」与「依赖就绪」应当分开判断，否则数据库抖动会引发容器被反复重启。
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://127.0.0.1:3000/actuator/health/liveness >/dev/null 2>&1 || exit 1
