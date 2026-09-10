# 微旅途后端 · MicroTripServer（JavaWeb / Spring Boot 重写版）

> 用 **Spring Boot 3.3 + Java 21** 重写原 Node.js（Express 5 + node:sqlite）后端，
> **保持与原版完全一致的 REST 契约**，Flutter 端 `AuthService` / `SyncService` 无需任何改动即可对接。

---

## 1. 技术选型（JavaWeb 最佳实践）

| 领域 | 选型 | 说明 |
|------|------|------|
| 语言 / 框架 | **Spring Boot 3.3 + Java 21** | 最新 LTS，虚拟线程就绪、记录类、模式匹配 |
| 构建 | **Maven** | 约定优于配置，父 POM 管理依赖版本 |
| Web | `spring-boot-starter-web` | 内嵌 Tomcat，REST 控制器 |
| 参数校验 | `spring-boot-starter-validation` | Jakarta Bean Validation（`@Valid`） |
| 持久化 | `spring-boot-starter-data-jpa` | Repository 抽象，零 SQL 样板 |
| 安全 | `spring-boot-starter-security` | 承载自定义 JWT 过滤器链（无状态） |
| 数据库 | **H2（文件型，默认）** / MySQL（profile） | 默认零外部依赖开箱即用；生产可切 MySQL |
| JWT | `jjwt` 0.12.x | HS256，与 App 端 payload 对齐 |
| 密码哈希 | Spring Security `BCryptPasswordEncoder` | cost 10，等价 bcryptjs |
| 文档 | `springdoc-openapi` | 自动生成 Swagger UI |
| 工具 | Lombok | 精简 DTO / 实体样板代码 |
| 健康检查 | `spring-boot-starter-actuator` | `/actuator/health` 供编排系统探活 |

---

## 2. 目录结构

```
MicroTripServerJava/
├── pom.xml
├── README.md
├── src/main/
│   ├── java/com/microtrip/server/
│   │   ├── MicroTripServerApplication.java        # 启动类
│   │   ├── common/                                # 通用层
│   │   │   ├── ApiError.java                       # {code,message}
│   │   │   ├── BizException.java                   # 业务异常（带 HTTP 状态/错误码）
│   │   │   └── GlobalExceptionHandler.java         # @RestControllerAdvice 统一包络
│   │   ├── config/                                # 装配
│   │   │   ├── SecurityConfig.java                 # 无状态 JWT 过滤器链 + 方法级角色鉴权 + CORS
│   │   │   ├── WebConfig.java / StaticCacheInterceptor.java  # food/scenery 只读缓存 + 限流注册
│   │   │   ├── RateLimitFilter.java / RateLimitProperties.java  # 登录/注册限流
│   │   │   └── VisionProperties.java               # 图像识别配置
│   │   ├── security/                              # 安全
│   │   │   ├── JwtPrincipal.java / JwtUtil.java / JwtSecretService.java（含 require-externalized fail-fast）
│   │   │   └── JwtAuthenticationFilter.java（注入 ROLE_* 权限）
│   │   ├── domain/                               # JPA 实体
│   │   │   ├── User.java（含 role）/ Trajectory.java / TrajectoryPoint.java（拆表）
│   │   ├── repository/                           # Spring Data JPA
│   │   │   ├── UserRepository.java / TrajectoryRepository.java / TrajectoryPointRepository.java
│   │   ├── dto/                                  # 请求/响应模型（含字段别名兼容）
│   │   ├── service/                              # 业务逻辑
│   │   │   ├── UserService / TrajectoryService / StaticDataService / AdminBootstrapService（启动注入管理员）
│   │   │   ├── VisionService / VisionProvider / TencentVisionProvider
│   │   ├── controller/                           # REST 接口
│   │   │   ├── HealthController / AuthController / TrajectoryController
│   │   │   ├── FoodController / SceneryController / VisionController
│   │   └── util/Haversine.java
│   └── resources/
│       ├── application.yml                       # 默认 H2 配置
│       ├── application-mysql.yml                 # MySQL profile
│       └── data/{food,scenery,shops}.json        # 静态数据集（搬自原 Node 版）
```

---

## 3. 接口对照（与原 Node 版一一对应）

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET  | `/health` | 公开 | 健康检查 |
| POST | `/auth/register` | 公开 | 注册 → `{token,user}`（201） |
| POST | `/auth/login` | 公开 | 登录 → `{token,user}` |
| POST | `/auth/logout` | Bearer | 登出 → `{ok:true}` |
| POST | `/trajectory/sync` | Bearer | 按 id 幂等 upsert → `{ok,id,points,stops}`（`points/stops` 为本轮新增附加字段） |
| POST | `/trajectory/sync-batch` | Bearer | 离线批量补传（≤20 条，逐条隔离失败）→ `{ok,total,succeeded,failed,results}` |
| GET  | `/trajectory/list` | Bearer | 摘要分页（不含 pts），支持 `?from=&to=`（毫秒时间戳）按开始时间过滤 |
| GET  | `/trajectory/stats` | Bearer | 汇总统计（轨迹数/总里程/总时长/累计爬升下降/采样点数），SQL 侧一次聚合 |
| GET  | `/trajectory/:id` | Bearer | 完整详情（含 pts/stops） |
| DELETE | `/trajectory/:id` | Bearer | 删除 |
| GET  | `/scenery/list` | 公开 | 景点列表（?city 注入） |
| GET  | `/scenery/detail/:id` | 公开 | 景点详情 |
| GET  | `/scenery/nearby/:id` | 公开 | 周边推荐（haversine 排序前 4） |
| GET  | `/food/list` | 公开 | 美食列表 |
| GET  | `/food/detail/:id` | 公开 | 美食详情 |
| GET  | `/food/shops` | 公开 | 推荐门店 |
| POST | `/vision/recognize` | Bearer | 拍照识物（未配置密钥 → 501） |
| POST | `/admin/ban` | Bearer(ADMIN) | 封禁用户 → `{ok:true}`（角色化，见 §5.1） |
| POST | `/admin/unban` | Bearer(ADMIN) | 解除封禁 → `{ok:true}` |

> **接口版本化（v1）**：所有业务接口同时暴露规范路径 `/api/v1/...`（如 `/api/v1/auth/login`、
> `/api/v1/trajectory/sync`、`/api/v1/admin/ban`）与历史裸路径（如 `/auth/login`）。
> 历史路径保留以**兼容已发布 Flutter 端（零改动）**；新接入客户端建议使用 `/api/v1`，
> 为不兼容契约升级预留空间（未来 `/api/v2` 可独立演进）。

统一错误包络：`{ "error": { "code": "...", "message": "..." } }`
所有时间字段为 **毫秒时间戳**；轨迹字段统一 `distance/duration/ascent/descent/avgSpeed`。

---

## 4. 快速开始

> 完整的环境搭建、部署与运维说明见 **[docs/部署指南.md](docs/部署指南.md)**，本节只给最短路径。

### 4.0 先跑环境自检
```bash
./scripts/check-env.sh
```
逐项检查 JDK 21 / Maven / Docker / 端口占用 / 配置文件，直接给出「缺什么、怎么补」。

### 4.1 本地开发（`scripts/dev.sh`）
```bash
./scripts/dev.sh          # 默认：文件型 H2，零外部依赖，开箱即用
./scripts/dev.sh mysql    # 连本地 MySQL 容器（需先 ./scripts/infra.sh up）
./scripts/dev.sh full     # MySQL + Redis，贴近生产拓扑
```
监听 `http://localhost:3000`，接口文档 `/swagger-ui.html`。

### 4.2 本地依赖容器（`scripts/infra.sh`）
```bash
./scripts/infra.sh up       # 起 MySQL 8 + Redis 7，自动等待健康检查通过
./scripts/infra.sh status   # 查看状态
./scripts/infra.sh mysql    # 进入 MySQL 命令行
./scripts/infra.sh down     # 停止（保留数据）
./scripts/infra.sh reset --yes   # 停止并清空数据卷
```
默认账号：MySQL `microtrip / microtrip123`（库 `microtrip`），Redis 无密码。可用 `.env` 覆盖。

### 4.3 本地生产态验证（`scripts/run-local.sh`）
```bash
./scripts/build.sh                     # 打包（含全量测试）
./scripts/run-local.sh full --bg       # 用成品 jar + prod profile 起，后台运行
./scripts/health.sh                    # 全链路冒烟：注册→同步→列表→统计→详情→删除→注销
./scripts/run-local.sh --stop          # 停止
```
这一步是**上服务器前的最后一道验收**：跑的是真实 jar 与生产 profile，能提前暴露
「开发环境能跑、生产参数起不来」这类问题（例如 prod 下 `JWT_SECRET` 未注入会直接拒绝启动）。

### 4.4 打包与容器镜像
```bash
./scripts/build.sh                 # mvn clean package（含测试）
./scripts/build.sh --skip-tests    # 跳过测试（生产出包请勿跳过）
./scripts/build.sh --docker        # 一并构建镜像
```
成品 jar：`target/micro-trip-server-boot.jar`（`pom.xml` 的 `finalName`，
与目录名区分，避免 Windows 下旧实例占用同名 jar 导致 `repackage` 重命名失败）。

### 4.5 部署到服务器（`scripts/deploy.sh`）
```bash
./scripts/deploy.sh --host root@1.2.3.4 --dry-run        # 先干跑校验，不动服务器
./scripts/deploy.sh --host root@1.2.3.4                  # jar + systemd
./scripts/deploy.sh --host root@1.2.3.4 --mode docker    # Docker Compose 全栈
```
两条路线的取舍、服务器首次初始化步骤见 [docs/部署指南.md](docs/部署指南.md) 第 6 章。

### 4.6 不使用脚本时的原始命令
```bash
mvn spring-boot:run                                          # 开发
mvn clean package && java -jar target/micro-trip-server-boot.jar   # 打包运行
java -jar target/micro-trip-server-boot.jar --spring.profiles.active=prod,mysql,redis
```
首次启动 `ddl-auto: update` 会自动建表。

### 4.7 构建注意事项（Windows）

- **文件锁**：`mvn package` 的 `spring-boot:repackage` 会先把 `target/*.jar` 重命名为 `*.jar.original` 再生成 fat jar。
  若**已有后端实例在运行并占用该 jar**（端口被占、进程未退出），Windows 无法重命名 → 报
  `Unable to rename ... to ...original`。**解决**：先停掉旧 `java -jar` 进程（或 `./scripts/run-local.sh --stop`），再重新 `mvn package`。
- **Git Bash 下必须用 `mvn.cmd`**：sh 版 `mvn` 会把 `MAVEN_HOME` 当 Unix 路径传给 Windows 原生 Java，
  报 `找不到或无法加载主类 Launcher`。`scripts/_common.sh` 已自动处理，手工敲命令时注意。
- **H2 2.x 配置**：`application.yml` 默认数据源使用 `jdbc:h2:file:./data/microtrip;DB_CLOSE_DELAY=-1`。
  **不要**再加 `AUTO_SERVER=TRUE` 或 `DB_CLOSE_ON_EXIT=FALSE`——H2 2.x 不允许 `AUTO_SERVER` 与 `DB_CLOSE_ON_EXIT` 同时出现，
  否则启动报 `Feature not supported: "AUTO_SERVER=TRUE && DB_CLOSE_ON_EXIT=FALSE"` 导致无法连接。

---

### 4.8 监控端点（Actuator + Prometheus）

后端内置 Spring Boot Actuator，默认暴露以下端点。
> ⚠️ **访问控制**：健康/信息类端点公开（编排探活依赖），而 `metrics`/`prometheus` 会暴露
> JVM 堆、连接池等待数、各接口耗时分布等内部信息，**仅允许白名单来源 IP**
> （`microtrip.security.actuator-allow-list`，默认 `127.0.0.1,::1` 即仅本机）。
> 判定只依据 TCP 来源地址、**不读 `X-Forwarded-For`**，伪造代理头无法绕过；
> 留空配置则为「谁都不放行」（fail-closed）。
> 容器编排下另外由 Nginx 只放行 `/actuator/health/**`，其余 `/actuator/` 直接 404。
>
> 注意不要改用 `management.server.address` 做这件事：该属性要求同时自定义
> `management.server.port` 才生效，同端口场景下是空操作（看起来加固了、其实没有）。
> 详见 [docs/部署指南.md](docs/部署指南.md) 第 7.3 与 9 章。

| 端点 | 方法 | 用途 |
| --- | --- | --- |
| `/actuator/health` | GET | 存活 / 就绪探针（K8s liveness / readiness） |
| `/actuator/info` | GET | 应用元数据 |
| `/actuator/metrics` | GET | Micrometer 指标列表（含 `http.server.requests` 等） |
| `/actuator/prometheus` | GET | Prometheus 拉取格式指标，已统一追加 `application="micro-trip-server"` 维度 |

Prometheus 抓取配置示例：

```yaml
scrape_configs:
  - job_name: 'micro-trip-server'
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['localhost:3000']
```

> 结构化日志：默认普通文本（文件 `application.log` 始终文本，便于排查）。若需 ELK / Loki 集中采集 JSON，
> 启动时加 `--spring.profiles.active=json`，控制台即输出 Logstash JSON；该 profile 仅切换日志格式，不影响任何业务行为。

---

## 5. 配置项（环境变量优先）

| 变量 | 默认 | 说明 |
|------|------|------|
| `PORT` | 3000 | 服务端口（`server.port`） |
| `JWT_SECRET` | 自动生成并写入 `.jwt_secret` | JWT 签名密钥（≥32 字符） |
| `CORS_ORIGIN` | `*` | 允许的跨域来源 |
| `VISION_PROVIDER` | `tencent` | 图像识别厂商：`tencent` \| `aliyun` \| `baidu` |
| `VISION_SECRET_ID` / `VISION_SECRET_KEY` | 空 | 腾讯云 tiia 密钥；缺失则 `/vision/recognize` 返回 501 |
| `VISION_REGION` | `ap-guangzhou` | 腾讯云地域 |
| `ALIYUN_AK` / `ALIYUN_SK` | 空 | 阿里云图像识别密钥（`provider=aliyun` 时启用） |
| `ALIYUN_REGION` | `cn-shanghai` | 阿里云地域 |
| `BAIDU_API_KEY` / `BAIDU_SECRET_KEY` | 空 | 百度图像识别密钥（`provider=baidu` 时启用） |
| `ADMIN_TOKEN` | 空 | **已废弃**：管理接口改为基于 JWT 角色的 OAuth2 风格鉴权（见 §5.1） |
| `ADMIN_BOOTSTRAP_PHONE` / `ADMIN_BOOTSTRAP_PASSWORD` | 空 | 启动时自动注入的管理员账号（角色 ADMIN）；生产建议留空，由 IdP/OAuth2 授予 ADMIN |
| `ADMIN_BOOTSTRAP_NICKNAME` | `超级管理员` | 引导管理员昵称 |
| `RATELIMIT_ENABLED` | `true` | 登录/注册限流开关（防爆破） |
| `RATELIMIT_CAPACITY` | `60` | 窗口内最大请求数（默认路径 `/auth/login`、`/auth/register` 及其 v1 路径） |
| `RATELIMIT_WINDOW` | `60` | 限流窗口（秒） |
| `REQUIRE_HTTPS` | `false` | `true` 时强制全站 HTTPS（建议配合 `https` profile 与前置代理） |
| `JWT_REQUIRE_EXTERNALIZED` | `false` | `true` 时若未通过 `JWT_SECRET` 注入密钥则启动失败（fail-fast，生产密钥托管） |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1:6379` | 仅 `redis` profile 生效（JWT 黑名单跨实例共享） |
| `DB_POOL_MAX` | `20` | Hikari 连接池上限（多副本部署时按 `副本数 × 20 < MySQL max_connections` 估算） |
| `DB_POOL_MIN_IDLE` | `5` | 常驻空闲连接数（避免突发流量时现开连接） |

> JWT 密钥解析优先级：`JWT_SECRET` > 文件 `.jwt_secret` > 启动时随机生成持久化，
> 保证重启后已签发 token 仍可校验（与原 Node 版一致）。

### 5.1 主动登出 / 封禁（Redis 黑名单 + 角色化管理员）

- 默认（非 `redis` profile）：`InMemoryRevocationService`，单实例可用，**登出即真正吊销当前令牌**（过滤链据此拒绝，即使 JWT 未过期）。
- 多实例 / 生产：加 `--spring.profiles.active=redis` 并配置 `REDIS_HOST/REDIS_PORT`，
  切换为 `RedisRevocationService`，吊销信息跨实例共享，TTL 自动过期。

**角色化封禁（演进）**：管理接口不再使用轻量 `X-Admin-Token` 闸门，改为基于 JWT 角色的
Spring Security 方法级鉴权：

- 管理员身份来自 JWT `role=ADMIN` 声明；`AdminController` 的 `ban/unban` 加
  `@PreAuthorize("hasRole('ADMIN')")`，普通用户（角色 USER）调用返回 **403**。
- 启动可按 `ADMIN_BOOTSTRAP_PHONE/PASSWORD` 注入一个管理员账号（角色 ADMIN）；
  **生产建议留空**，由 IdP / OAuth2 / 配置中心把 ADMIN 角色授予真实员工，本服务只认 JWT 声明。
- 调用示例（管理员令牌）：

```bash
# 封禁
curl -X POST http://localhost:3000/api/v1/admin/ban \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"userId":"u_xxx","ttlSeconds":3600}'
# 解除封禁
curl -X POST http://localhost:3000/api/v1/admin/unban \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"userId":"u_xxx"}'
```

被封禁用户的所有令牌在过滤链返回 **403**，实现「主动封禁」。

### 5.2 登录 / 注册限流（防爆破）

登录与注册接口默认启用固定窗口限流（内存实现，单实例）：同一「客户端 IP + 路径」在
`RATELIMIT_WINDOW` 秒内超过 `RATELIMIT_CAPACITY` 次即返回 **429**（`Retry-After` 头 + `RATE_LIMITED`）。
多实例生产建议换 Redis 令牌桶（Bucket4j + Redis）。可用 `RATELIMIT_ENABLED=false` 关闭（测试默认关）。

### 5.3 强制 HTTPS（生产）

```bash
java -jar target/micro-trip-server-boot.jar --spring.profiles.active=https
```

`https` profile 下 `microtrip.security.require-https=true`，Security 链对所有请求 `requiresSecure()`，
非 HTTPS 访问被重定向到 https（依赖前置 Nginx / 网关转发 `X-Forwarded-*`）。若后端直接终止 TLS，
在 `application-https.yml` 的 `server.ssl.*` 填入由 KMS / 配置中心注入的密钥（勿硬编码）。

### 5.4 密钥托管（生产 fail-fast）

JWT 密钥解析优先级：`JWT_SECRET` 环境变量 > 文件 `.jwt_secret` > 启动时随机生成持久化。
生产建议设置 `JWT_REQUIRE_EXTERNALIZED=true`（配合 `prod` profile）：若未通过 `JWT_SECRET` 注入密钥，
启动即失败，强制从 KMS / 配置中心外部化，避免把随机/文件密钥用于生产。

### 5.2 切换图像识别厂商

```bash
# 使用百度识图（需 BAIDU_API_KEY / BAIDU_SECRET_KEY）
java -jar target/micro-trip-server-boot.jar --microtrip.vision.provider=baidu \
  -DBAIDU_API_KEY=xxx -DBAIDU_SECRET_KEY=yyy
```
未配置对应厂商密钥时，`/vision/recognize` 仍返回 501（不伪造结果）。

---

## 6. 与 Flutter 端对接

- App 设置页「账号与后端服务」填入 `http://<host>:3000`，登录/注册/轨迹同步自动切换真实后端。
- 发现页（美食/景点）提供「模拟数据开关」：关闭开关且已配置后端时从本服务拉取，失败自动降级本地 mock。
- 拍照识物（Vision）**无 mock**：本服务未配置密钥时返回 501，App 提示用户配置，不伪造结果。
- 接入真实腾讯云识图：`TencentVisionProvider.recognize()` 已实现 TC3-HMAC-SHA256 签名并调用 `DetectLabel`，
  将返回标签映射为 `VisionLabel` 列表（置信度归一化到 0-1），无需 mock。

---

## 7. 与原 Node 版的主要差异

| 项 | Node 版 | Java 版 |
|----|---------|---------|
| 语言/框架 | Express 5 + node:sqlite | Spring Boot 3.3 + JPA |
| 数据库 | SQLite（node:sqlite） | H2 文件库（默认）/ MySQL（profile） |
| 路由 | 手动 `express.Router` | `@RestController` + 注解 |
| 鉴权 | 自写 `requireAuth` 中间件 | Spring Security 无状态 JWT 过滤器链 |
| 登出/封禁 | 仅客户端丢弃 token | 服务端 `RevocationService` 黑名单：主动登出吊销令牌、管理员封禁用户（Redis/内存双实现） |
| Vision | 腾讯云调用 | `VisionProvider` 抽象 + 按 `microtrip.vision.provider` 注入 tencent/aliyun/baidu（未配置即 501） |
| 自动化测试 | 无 | Spring Boot 测试 68 用例：`ApiContractTest`（19，v1 契约与演进项）+ `TrajectoryOptimizationTest`（13，性能治理回归）+ `StatsClientContractTest`（6，stats 客户端字段契约）+ `IpAllowListTest`（17，Actuator 白名单纯逻辑）+ `ActuatorAccessTest`（12，访问控制端到端）+ `RateLimitTest`（1） |
| 静态数据 | 运行时读 JSON 文件 | 启动时加载 classpath 资源 + 按城市缓存「已注入」结果 |
| 接口版本化 | 无 | 业务接口同时暴露 `/api/v1/...`（规范）与历史裸路径（Flutter 零改动兼容），为不兼容升级预留空间 |
| 数据落库 | `points_json` 单 JSON 列 | 保留 `points_json`（契约规范存储）外，新增独立 `trajectory_points` 表，支持 SQL 侧范围检索/密度统计，删轨迹/注销均级联清理 |
| 查询优化 | 无 | 列表/统计走构造器投影（不读大字段）、批量删除直发 SQL、JDBC 批量写入、响应压缩（见 §8） |
| 安全加固 | 无 | 角色化封禁（`@PreAuthorize("hasRole('ADMIN'")`，替代 X-Admin-Token）、登录/注册限流（429）、HTTPS 强制（https profile）、JWT 密钥生产外部化 fail-fast |

> 所有**对外 REST 契约、字段命名、错误码、JWT payload、分页规则**均与原版保持一致。

---

## 8. 性能设计（2026-09-10 优化轮）

轨迹是本项目最"重"的数据：单条轨迹最多 20000 个 GPS 点。以下八项围绕它做了系统性治理，
每项都对应一个具体的资源浪费点。

| # | 问题（优化前） | 处理方式 | 效果 |
|---|---------------|---------|------|
| 1 | 列表接口用实体查询，把 `points_json` 大字段整列读出（一页 20 条可达数十 MB） | 改 JPQL 构造器投影，只 SELECT 10 个摘要列 | 列表 IO / 堆占用与「点数」彻底解耦；由测试 `list_returnsSummaryOnly_withoutPointsJson` 钉死 |
| 2 | 删轨迹/注销用派生删除：先 SELECT 全部实体再逐条 DELETE | 改 `@Modifying @Query` 直发单条 DELETE | 20000 点轨迹的删除由「2 万次 DELETE + 2 万个实体驻留内存」变为 1 条 SQL |
| 3 | 批量插入未开 JDBC batching，20000 点 = 20000 次单条 INSERT | `hibernate.jdbc.batch_size=500` + `order_inserts`；MySQL 侧补 `rewriteBatchedStatements=true` | 本机 H2 实测 5000 点：**880 ms → 645 ms**；MySQL 因省掉网络往返，收益更大 |
| 4 | 注销只删 `trajectories`，`trajectory_points` 残留孤儿行 | 注销流程补删 GPS 点（**顺序必须是先点子表后轨迹表**，子查询依赖归属关系） | 修复真 bug：注销即彻底删除，由 `deleteAccount_purgesOrphanTrajectoryPoints` 守住 |
| 5 | 静态数据每次请求都整表做 `{city}` 字符串注入 | 按城市缓存注入结果（`ConcurrentHashMap` + 容量上限，无锁读） | 命中后只做一次查表 |
| 6 | 周边推荐先把距离格式化成 `"3.2km"` 再 parse 回数值排序 | 直接按 km 数值排序，最后一步才格式化 | 去掉字符串往返；同时修掉 1 位小数取整导致的排序不稳定 |
| 7 | 连接池/超时使用默认值（取连接超时 30s） | Hikari 显式配置：池 20、取连接 3s 快速失败、`max-lifetime < MySQL wait_timeout` | 慢查询下客户端不再干等 30 秒 |
| 8 | 详情接口返回数 MB JSON 不压缩 | `server.compression` 开启（≥1KB 才压，JSON 压缩比通常 8~15×） | 带宽与首屏等待下降 |

另外：列表 / 详情 / 统计均标注 `@Transactional(readOnly = true)`，Hibernate 跳过脏检查快照，
减少内存分配与 CPU；`sync-batch` 用编程式 `TransactionTemplate` 实现「一条轨迹一个事务」，
而不是 `@Transactional(REQUIRES_NEW)` 注解（类内自调用不走 AOP 代理，会静默失效）。

### 8.1 批量同步的失败隔离语义

`POST /trajectory/sync-batch` 的 `ok: true` 只表示「请求被受理」，**不等价于全部成功**：

- `succeeded` / `failed` 给出条数，`results[]` 逐条给出成败与失败原因；
- 已是分批补传场景（App 离线攒了多条轨迹），语义上「尽力而为」比「全有全无」有用得多：
  一条脏数据不该让另外 19 条好数据一起传不上去；
- 客户端拿到响应后**只需重试 `ok:false` 的那些 id**。

用例 `syncBatch_partialFailure_doesNotRollbackOthers` 刻意把两条坏数据（缺 id、id 超列宽）
夹在两条好数据中间，断言后面的那条仍然落库 —— 这是对「逐条独立事务」的正面验证。

