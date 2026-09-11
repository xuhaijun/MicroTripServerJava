# IntelliJ IDEA JavaWeb 开发常用操作指南（MicroTripServerJava）

> 面向在 IntelliJ IDEA 里开发本 Spring Boot 后端：导入、运行/调试、Profile、Maven、数据库连接。
> 端口约定：后端 **3000**；MySQL **3306**（库 `microtrip`）；Redis **6379**。

## 1. 导入项目
1. `File → Open`，选中 `D:\FlutterProjects\MicroTripServerJava`（IDEA 识别 `pom.xml` 自动按 Maven 项目打开）。
2. 首次打开等右下角 **Maven 导入**完成（依赖在本地 `.m2`，走阿里云镜像，离线也能编）。
3. **Project SDK 必须选 21**：`File → Project Structure → Project → SDK` → 选 `C:\Program Files\Java\jdk-21.0.10`。
   - ⚠️ 本机还装了 `jdk-18.0.1.1`，**别选错**；Spring Boot 3.3 要求 Java 21。
4. Maven 设置：`File → Settings → Build → Maven`：Maven home 选 `C:\Program Files\apache-maven-3.9.9`，勾选离线可加快。

## 2. 运行 / 调试 Spring Boot
1. 打开主类 `XxxApplication`（搜 `SpringApplication.run` 或 `*/Application.java`）。
2. 左上 Run/Debug 配置下拉 → `Edit Configurations → + → Spring Boot`（或 `Application`）。
3. **激活 Profile**（关键，决定连 H2 还是 MySQL/Redis）：
   - `VM options`：`-Dspring.profiles.active=dev,mysql,redis`
   - 或 `Program arguments`：`--spring.profiles.active=dev,mysql,redis`
   - 环境变量面板可设 `SERVER_PORT=3000`、`REDIS_PORT=6379` 等。
4. 运行 `Shift+F10`，调试 `Shift+F9`（断点左键点行号槽）。
5. 热更新：Run 配置 → `On 'Update' action` / `On frame deactivation` 选 `Update classes and resources`（配合 `spring-boot-devtools` 更顺）。

> 联调 Flutter：模拟器访问宿主机用 `http://10.0.2.2:3000`；真机同一局域网用本机局域网 IP。

## 3. 数据库连接（Database 工具窗）
右侧 `Database` 标签（或 `View → Tool Windows → Database`）：

### 3.1 连 MySQL
1. `+ → Data Source → MySQL`。
2. 填：`Host=127.0.0.1`、`Port=3306`、`User=microtrip`、`Password=microtrip123`、`Database=microtrip`。
3. 驱动：IDEA 自带 MySQL Connector/J；首次连可能提示下载，点 Download 即可。
4. **时区**：若报 `The server time zone value ...`，Advanced 里设 `serverTimezone=Asia/Shanghai`，或在 URL 加 `?serverTimezone=Asia/Shanghai`。
5. 连不上先排查：MySQL 服务起了没（`net start MySQL84`）、端口 3306 监听没、是否被系统代理拦截（连本地用 `127.0.0.1` 并确认 IDEA 代理设置 `No proxy for 127.0.0.1`）。
6. 连上后可：执行 SQL 控制台、看表结构/数据、导出 CSV、对比数据——替代命令行。

### 3.2 连 Redis
IDEA 社区版**不内置 Redis 视图**。两种办法：
- 命令行：`C:\Program Files\Redis\redis-cli.exe -h 127.0.0.1 -p 6379`。
- 装插件：Marketplace 搜 `Redis` / `IEDIS`（部分收费）；或装独立 GUI `AnotherRedisDesktopManager`（本机未装，需自行安装）。

## 4. 常用操作速查
| 操作 | 入口 / 快捷键 |
|---|---|
| 全局搜索类/文件/符号 | `Shift+Shift` |
| 跳到定义 | `Ctrl+单击` / `Ctrl+B` |
| 查找用法 | `Alt+F7` |
| Maven 生命周期（clean/compile/test/package） | 右侧 `Maven` 面板 → 项目 → `Lifecycle` |
| 终端 | `Alt+F12`（内置 Terminal，可直接跑 `mvn.cmd`/`git`/`./scripts/*.sh`） |
| Git 提交/推送 | 左侧 `Commit` / `Git → Push`（双远程 origin+gitee） |
| 条件断点 | 断点右键 → `Condition` |
| 异常断点 | `Run → View Breakpoints → Java Exception Breakpoints` |
| 重新编译单文件 | `Ctrl+Shift+F9` |

## 5. 调试技巧
- **条件断点**：在循环/高频方法上右键断点设 `Condition`（如 `userId.equals("13900000001")`），避免狂 F9。
- **Evaluate Expression**：调试暂停时 `Alt+F8` 随时求值（看对象字段、试表达式）。
- **Watch**：把关心的变量加到 Watch 窗口，单步时实时看。
- **Spring Boot 端点**：调试时可临时开 `management.endpoints.web.exposure.include=*`，访问 `/actuator/` 看 beans/mappings/env（注意 `metrics/prometheus` 受 IP 白名单限制）。

## 6. 常见坑
| 问题 | 解决 |
|---|---|
| 编译报「不支持发行版本 21」 | Project SDK 选成 18 了 → 改成 21；`pom.xml` 的 `java.version` 也是 21 |
| Profile 没生效 / 连的还是 H2 | Run 配置里没加 `--spring.profiles.active`；或改了 yml 没**重打包**（内嵌配置以 jar 内为准） |
| 端口 3000 被占 | `netstat -ano | findstr :3000` → `taskkill /PID <pid> /F`；或 `SERVER_PORT=3001` |
| 连本地 MySQL 报 502 / 超时 | IDEA 走了系统代理；设置 `No proxy for 127.0.0.1,localhost` 后用 `127.0.0.1` |
| 断点不生效 | 跑了没加载 devtools 的热替换；或类被代理/AOP，勾 `Force step into`；重新 Debug |
| 依赖下不下来 | Maven 设了阿里云镜像（`settings.xml`）；离线开发确保 `.m2` 已缓存，勾 Maven 离线 |

## 7. 部署 / 验证闭环（IDEA 外）
- 打包：Maven 面板 `package`（或 `./scripts/build.sh`）→ `target/microtrip-server-*.jar`。
- 本地跑：`java -jar target/*.jar --spring.profiles.active=prod,mysql,redis`。
- 健康检查：`curl localhost:3000/actuator/health` 或 `./scripts/health.sh`。
- 数据库体检：`./scripts/backup.sh --local`（先验证可连再备份）。
