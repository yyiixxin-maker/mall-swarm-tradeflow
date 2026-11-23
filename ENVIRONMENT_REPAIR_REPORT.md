# TradeFlow 第二轮环境修复报告

日期：2026-11-16（Asia/Shanghai）。  
依据：根目录 `BASELINE_AUDIT.md`、`BASELINE_ENV_REPORT.md`，以及本轮实际执行结果。  
分支：`feature/tradeflow-mvp`。提交：`04c442fe318356bae4445a109dde7af297ce1e84`。

**最新复验（2026-11-16 14:49，Asia/Shanghai）：用户手动处理后，系统 PATH 的失效 Oracle Java 入口已移除，当前进程及持久环境的 java/javac 均解析到 Temurin 17；Maven 使用 Java 17。Docker Engine 和 Compose 均可用，五项版本命令全部退出码 0。项目编译、测试及中间件兼容性仍未验收。**

第 1–9 节保留此前修复现场记录，其中旧版本、Engine 不可达和待手动处理的描述属于当时状态；最新状态以第 10 节为准。

## 1. 已完成的修复

1. 确认 `winget --version` 可执行，版本为 `v1.29.380`。查询 Temurin 17 包成功；查询 `Apache.Maven` 时本机源提示更新失败且未找到匹配包，因此 Maven 使用 Apache 官方 ZIP 安装。
2. 检查 PATH、Java 注册表、常见安装目录及 JDK release 文件，未找到可用 JDK 17。原 Oracle Java 启动器异常，注册表所指 JDK 20 目录不存在；PyCharm 内置 JBR 21 不作为本项目的构建 JDK。
3. 从 Adoptium 官方资产 API 获取 **Eclipse Temurin JDK 17.0.21+9 Windows x64**，校验 SHA-256 后在当前用户目录解压安装。
4. 从 Apache 官方下载 **Maven 3.9.16**，校验官方 SHA-512 后在当前用户目录解压安装。没有选择 3.10.x 或 Maven 4。
5. 在 `HKCU\Environment` 保存当前用户的 `JAVA_HOME`、`MAVEN_HOME`，将两个工具的 bin 目录加入用户 PATH 前部，保留原有用户 PATH 内容及注册表值类型；未修改系统 PATH。
6. 在验证进程中加载上述用户变量，并把 JDK/Maven 的 bin 放在进程 PATH 前部。`java -version`、`javac -version`、`mvn -version` 全部退出码 0；Maven 报告 Java 17.0.21、vendor 为 Eclipse Adoptium。
7. 找到 Docker Desktop 自带的可用 Compose 插件，将原有二进制复制到当前用户 `.docker\cli-plugins` 标准发现目录，恢复 `docker compose`。没有修改 `.docker/config.json` 或仓库 Compose 文件。
8. 使用普通用户权限启动已有 Docker Desktop，复查 Engine，并对两份原始 Compose 执行 `config --quiet`：均退出码 0。没有执行 Compose up、创建/启动数据库容器或操作数据卷。

Temurin 的 MSI 官方设计为全机安装，本轮采用官方 ZIP 的用户目录安装方式，不需要提升权限。依据：[Adoptium Windows 安装说明](https://adoptium.net/installation/windows/)、[官方归档安装说明](https://adoptium.net/installation/archives)、[Apache Maven 下载页](https://maven.apache.org/download.cgi)。

环境变量写入首次在广播环境变更时超时；核对确认当时只有 JAVA_HOME 已写入，随后通过当前用户注册表环境项完成其余设置并读回验证。没有把首次超时记作成功，也没有更改系统变量。旧进程不会自动获得新的完整环境，后文提供重新加载方法。

## 2. 安装的软件及版本

| 软件 | 状态及版本 | 位置 |
| --- | --- | --- |
| Eclipse Temurin JDK | 本轮新装，17.0.21+9，x64，HotSpot | `%USERPROFILE%\AppData\Local\Programs\Eclipse Adoptium\jdk-17.0.21+9` |
| Apache Maven | 本轮新装，3.9.16 | `%USERPROFILE%\AppData\Local\Programs\Apache Maven\apache-maven-3.9.16` |
| winget | 原有，v1.29.380，已实测可用 | 当前用户 WindowsApps 的 winget.exe |
| Docker Desktop | 原有，文件 ProductVersion 为 4.27.2.137060，未升级 | `C:\Program Files\Docker\Docker\Docker Desktop.exe` |
| Docker CLI | 原有，25.0.3，build 4debf41，未升级 | `C:\Program Files\Docker\Docker\resources\bin\docker.exe` |
| Docker Compose | 原有二进制，v2.24.5-desktop.1；本轮修复用户插件入口 | `%USERPROFILE%\.docker\cli-plugins\docker-compose.exe` |

下载校验结果均匹配：

```text
Temurin ZIP SHA-256:
98518ecf554465057ee5509462631deeee374bc368c12a411d225cd29381f84b

Maven ZIP SHA-512:
ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3
```

官方 JDK 元数据取自 `api.adoptium.net/v3/assets/latest/17/hotspot` 的 Windows/x64/JDK/Eclipse 查询；Maven 使用 Apache 下载页提供的 3.9.16 ZIP 与 SHA-512。解压前检查归档路径必须位于预期软件目录内，拒绝符号链接和覆盖已有软件目录；解压后核对 java.exe、javac.exe、mvn.cmd 存在。

## 3. JAVA_HOME、PATH 和 Maven 状态

已持久化到当前用户：

```text
JAVA_HOME=%USERPROFILE%\AppData\Local\Programs\Eclipse Adoptium\jdk-17.0.21+9
MAVEN_HOME=%USERPROFILE%\AppData\Local\Programs\Apache Maven\apache-maven-3.9.16
```

用户 PATH 前两项为这两个目录各自的 `bin`。没有更改 POM 的 `<java.version>17</java.version>`，没有切换到 JBR 21，也没有更改 Spring Boot 3.5.14、Spring Cloud 2025.0.2 或 Alibaba 2025.0.0.0。

**持久命令解析仍有一个明确缺口。** 系统 PATH 在用户 PATH 之前包含以下失效入口：

```text
C:\Program Files\Common Files\Oracle\Java\javapath
C:\Program Files (x86)\Common Files\Oracle\Java\javapath
```

本轮用“系统 PATH + 用户 PATH”模拟新登录环境，实际 `Get-Command` 仍将 java/javac 解析到第一条 Oracle 路径；mvn 则能解析到新装 Maven。Maven 通过用户 JAVA_HOME 使用 JDK 17，但新终端直接执行 java/javac 仍可能失败。因此不能声称所有新终端已永久修复。

当前进程的版本验证是在显式加载用户 JAVA_HOME/MAVEN_HOME、优先放置两个 bin 后执行的，命令解析路径为新装软件，不是绝对路径调用后冒充 PATH 已生效。

### 需要用户完成的系统 PATH 操作

本轮进程不是管理员，且授权范围是当前用户环境变量，没有尝试改写机器级 PATH。

1. 按 **Win+R**，输入 `SystemPropertiesAdvanced`，打开“系统属性 → 高级 → 环境变量”。
2. 在下方“系统变量”中选中 **Path → 编辑**，移除上面两条失效 Oracle `javapath` 入口。这里只移除 PATH 条目，不删除目录或其他程序；保留其他系统 PATH 项。
3. 如 Windows 请求管理员凭据，由你或管理员确认完成；本轮没有尝试提权或绕过权限。
4. 确认保存后，关闭旧 Codex/Terminal/PowerShell 并重新打开。为确保所有父进程加载完整环境，必要时注销并重新登录 Windows。
5. 执行 `Get-Command java,javac,mvn` 及三条版本命令，确认路径指向本报告的用户目录。

如果暂时不能修改系统 PATH，在需要构建的 PowerShell 窗口执行以下进程级设置即可；它不修改系统 PATH，也不是永久修复：

```powershell
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
$env:MAVEN_HOME = [Environment]::GetEnvironmentVariable('MAVEN_HOME', 'User')
$env:Path = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:Path"
Get-Command java,javac,mvn
java -version
javac -version
mvn -version
```

## 4. Docker 状态与手动操作

Docker Desktop 已安装。本轮以普通权限调用 `Start-Process` 启动，启动命令返回成功，随后实际观察到 Docker Desktop 和 com.docker.backend 进程。**进程启动不等于 Engine 成功启动。**

最终检查：

- 默认 context 的 `docker_engine` 管道仍不存在，`docker version` 退出码 1，没有 Server 版本。
- `desktop-linux` context 的 `dockerDesktopLinuxEngine` 管道也不存在；没有通过切换 context 掩盖错误。
- `docker ps --all` 无法读取容器清单，既有容器/卷状态未知。
- 启动前 `wsl --list --verbose` 能列出 docker-desktop-data、docker-desktop、Ubuntu-24.04，均为 WSL 2、Stopped；不能据此判断启动后的全部状态。
- 后续服务实查：`WslService=Running`、`vmcompute=Running`、`com.docker.service=Stopped/Manual`、`LxssManager=NotFound`。
- 附带的旧诊断程序 `com.docker.diagnose.exe check` 在 20 秒后超时；其中 LxssManager 检查报失败，文件系统检查报 `context deadline exceeded`。实际 WslService 正在运行，因此不能据旧服务名检查认定 WSL 服务未运行，也不能把诊断超时认定为文件系统损坏。
- `com.docker.service` 停止本身不能证明是 WSL 后端失败根因。本轮没有取得明确根因或管理员提示的直接证据。

Compose 已修复，以下命令实际通过，退出码均为 0，校验输出为空：

```text
docker compose -f document/docker/docker-compose-env.yml config --quiet
docker compose -f document/docker/docker-compose-app.yml config --quiet
```

这是配置校验成功，不是容器运行成功。原始环境/应用 Compose 未修改。

### 需要用户进行的 Docker 界面检查

1. 按 **Windows 键**，搜索并打开 **Docker Desktop**，查看 Dashboard 的 Engine 状态及启动错误；若显示可启动按钮，点击启动并等待状态变化。本轮已经尝试后台启动，不建议反复启动多个实例。
2. 如果界面出现管理员/UAC、Windows 功能、WSL 更新或服务权限提示，暂停后由你或管理员按该具体提示处理。本轮没有批准提权、改变服务权限、重装 Desktop 或执行 WSL 更新。
3. **仅当界面明确要求启动 Docker 系统服务时**，右键开始菜单 → “终端（管理员）”，执行以下命令；不要把该操作视为已确认适用于所有 WSL 问题的修复：

   ```powershell
   Start-Service -Name com.docker.service
   Get-Service -Name com.docker.service
   ```

4. 返回 Docker Desktop，确认 Engine 已运行后，在普通 PowerShell 执行：

   ```powershell
   docker version
   docker --context desktop-linux version
   docker compose version
   ```

成功标准是 `docker version` 同时给出 Client 和 Server，且退出码 0。若界面仍停在 Starting，应依据界面具体错误继续诊断；当前不能承诺启动服务或更新组件一定修复。

既有 docker-desktop-data 发行版表明机器上有原有数据环境。本轮没有执行 factory reset、prune、WSL unregister、数据盘修复或重建，没有启动可能覆盖现有数据的数据库容器。

## 5. 五项最终版本命令的真实结果

执行位置为仓库根目录；Java/Maven 命令使用第 3 节说明的验证进程环境。退出码按各条命令单独记录。

### `java -version`：退出码 0

```text
openjdk version "17.0.21" 2026-10-20
OpenJDK Runtime Environment Temurin-17.0.21+9 (build 17.0.21+9)
OpenJDK 64-Bit Server VM Temurin-17.0.21+9 (build 17.0.21+9, mixed mode, sharing)
```

### `javac -version`：退出码 0

```text
javac 17.0.21
```

### `mvn -version`：退出码 0

```text
Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
Maven home: %USERPROFILE%\AppData\Local\Programs\Apache Maven\apache-maven-3.9.16
Java version: 17.0.21, vendor: Eclipse Adoptium, runtime: %USERPROFILE%\AppData\Local\Programs\Eclipse Adoptium\jdk-17.0.21+9
Default locale: zh_CN, platform encoding: GBK
OS name: "windows 11", version: "10.0", arch: "amd64", family: "windows"
```

Maven 的原生输出按其 GBK 编码正确捕获，未通过修改项目编码或 JVM 参数掩盖中文路径问题。这里验证的是 Maven 运行时 JDK，未执行项目编译。

### `docker version`：退出码 1

stdout：

```text
Client:
 Cloud integration: v1.0.35+desktop.10
 Version:           25.0.3
 API version:       1.44
 Go version:        go1.21.6
 Git commit:        4debf41
 Built:             Tue Feb  6 21:13:02 2024
 OS/Arch:           windows/amd64
 Context:           default
```

stderr：

```text
error during connect: this error may indicate that the docker daemon is not running: Get "http://%2F%2F.%2Fpipe%2Fdocker_engine/v1.24/version": open //./pipe/docker_engine: The system cannot find the file specified.
```

### `docker compose version`：退出码 0

```text
Docker Compose version v2.24.5-desktop.1
```

## 6. MySQL / Nacos / RabbitMQ 待决策事项

以下是兼容性评审，没有实施版本或配置变更。本轮 127.0.0.1 的 3306、6379、5672、15672、8848、9848 均实测 `ECONNREFUSED`；没有登录数据库、消息服务或 Nacos。

### MySQL

原始 Compose 仍为 `mysql:5.7`，POM 仍声明 `com.mysql:mysql-connector-j:9.3.0`。官方说明 9.3.0 支持 MySQL Server 8.0 及之后版本，因此与仓库 5.7 目标存在支持范围缺口；这不是本轮复现的 JDBC 错误。实际 Server 版本未知，未执行 `SELECT VERSION()`。依据：[Connector/J 9.3.0 发布说明](https://dev.mysql.com/doc/relnotes/connector-j/en/news-9-3-0.html)。

| 待选方案 | 兼容性及适用范围 | 决策前提 |
| --- | --- | --- |
| 保留 Connector/J 9.3.0，评审 MySQL 8.4 LTS 作为隔离目标 | 服务器位于 9.3.0 驱动声明支持的 8.0+ 范围；优先评审这条路径以减少项目依赖变动 | 先确认真实数据/版本、备份及迁移计划，隔离数据目录，验证认证、SQL 与业务兼容性；本轮不升级数据库 |
| 保留 MySQL 5.7，评审 Connector/J 8.0.33 | 官方明确该历史驱动支持 Server 5.7 / 8.0；可作为保留旧数据库时的兼容候选 | 需要用户批准后才能改 POM，并单独评审旧版本维护与安全风险；不是默认长期方案 |

依据：[Connector/J 8.0.33 官方兼容说明](https://dev.mysql.com/doc/relnotes/connector-j/en/news-8-0-33.html)。MySQL 官方升级路径要求 5.7 → 8.0 → 8.4，不能直接换镜像并复用 5.7 数据目录越级升级。依据：[MySQL 8.4 升级路径](https://dev.mysql.com/doc/refman/8.4/en/upgrade-paths.html)。

现有 SQL 含 DROP TABLE，本轮未导入。后续环境初始化须单独审阅数据保护方案。

### Nacos

- Compose 仍为 Server `v2.1.0`；项目 Alibaba BOM 为 `2025.0.0.0`，其官方组件表对应 Nacos Client `3.0.3`。尚未运行 Maven 依赖解析，不能把上游预期版本写成本机已解析版本。依据：[Alibaba 组件版本关系](https://sca.aliyun.com/docs/2025.x/overview/version-explain/)。
- 项目未显式设置 Nacos config/discovery namespace/group，按默认 public / DEFAULT_GROUP 推断。官方说明 Alibaba 2025.0.x 使用 public 或空 namespace 时需 Nacos Server 3.x；2.1.0 目标应重新评审。建议保持项目 Boot/Cloud/BOM 声明，另行选定兼容的 Nacos Server 和隔离方案，本轮没有升级。依据：[Alibaba Nacos 重大变化说明](https://sca.aliyun.com/docs/2025.x/user-guide/nacos/quick-start/)。
- 原始 Compose 仍只映射 `8848:8848`，没有 `9848:9848`。宿主机客户端需要可达的客户端 gRPC 端口，默认由主端口 +1000 计算；Docker 网络内直连与宿主机映射应分别验证。依据：[Nacos gRPC 端口说明](https://www.nacos.io/en/docs/v2.5/upgrading/200-compatibility/)。
- `mall-auth-dev.yaml`、`mall-auth-prod.yaml` 被 auth profile 显式导入，但仓库没有对应 `config/auth/` 快照。实际 Nacos 是否已存在这两个 DataId 未核验，不能直接判定线上缺失，也没有编造空配置。
- 后续需要从已授权 Nacos 读取这两份配置并脱敏，核对所有导入 DataId、真实 namespace ID、config/discovery group、服务注册和健康实例，并确定持久化/恢复方式。

### RabbitMQ

- portal 仍要求 `/mall` vhost、`mall` 用户。原始 Compose 没有相关默认用户/vhost环境项、definitions 导入或权限初始化脚本；仓库没有 `rabbitmq-definitions.json`。
- 代码预期的拓扑仍是 `mall.order.direct.ttl → mall.order.cancel.ttl → DLX mall.order.direct → mall.order.cancel`，通过消息 expiration 延迟。已有声明及消息体没有修改。
- 实际用户、vhost、configure / write / read 权限、队列 durable/arguments、绑定、policy、积压均未知。后续须先只读核验旧 broker，不能为了初始化删除原有队列或覆盖消息。
- 旧 `publisher-confirms` 配置和有效发布确认设置仍需后续评审，本轮没有修改。

Engine 可用且确认目标环境后，可以执行以下只读检查；这些命令本轮未成功执行：

```text
docker exec rabbitmq rabbitmqctl list_vhosts name
docker exec rabbitmq rabbitmqctl list_users
docker exec rabbitmq rabbitmqctl list_user_permissions mall
docker exec rabbitmq rabbitmqctl list_permissions -p /mall
docker exec rabbitmq rabbitmqctl list_queues -p /mall name durable arguments messages consumers
docker exec rabbitmq rabbitmqctl list_bindings -p /mall
docker exec rabbitmq rabbitmqctl list_policies -p /mall
```

用户创建、授权及口令注入方案需在确认目标后制定；本轮没有生成、输出或提交真实密码/密钥。

## 7. 仍然存在的环境阻塞

| 阻塞/未完成验证 | 当前状态 | 下一步 |
| --- | --- | --- |
| 新登录环境的 java/javac 优先级 | 用户变量已保存，系统 Oracle 路径仍优先 | 按第 3 节清理失效系统 PATH；或在需要构建的窗口使用进程级前缀 |
| Docker Engine | Desktop/后台进程存在，两个 context 均不可连接 | 按第 4 节查看界面具体错误；如需管理员操作，由用户/管理员执行 |
| 中间件运行证据 | 本机相关端口均拒绝连接，既有容器清单无法读取 | 先确认运行目标和数据保护，再决定部署；本轮未启动中间件 |
| MySQL / Nacos 版本方案 | 原声明兼容缺口仍在 | 用户确认方案后单独变更与验收 |
| RabbitMQ 初始化及 auth DataId | 交付缺口未修，运行状态未知 | 从目标环境只读核验，准备脱敏和可复现初始化资料 |
| 项目编译、最终依赖树及测试 | 本轮仅做工具版本验证，未执行编译/测试 | 下一阶段按下列命令开始；不能把 mvn -version 成功写成项目编译通过 |

## 8. 下一步 Maven 编译命令

确认 JAVA_HOME/命令解析后，在仓库根目录优先执行：

```powershell
Set-Location -LiteralPath '.'
mvn -pl mall-portal -am -DskipTests=true test
```

系统 PATH 尚未清理时，先执行第 3 节的进程级环境加载代码。该命令可以先验证源码/测试源码编译，不要求先把业务应用或中间件启动起来；依赖下载是否可用仍需实际运行确认。

根 POM 默认 `skipTests=true`。上述命令不执行测试用例，不能作为测试通过证据；真正测试须在隔离依赖和数据准备后显式使用 `-DskipTests=false`。本轮未执行 Maven compile/test/package/install/deploy，也未产生 Maven 编译成功结论。

继续避免直接执行 `package`：根 POM 仍将远程 Docker 镜像构建绑定在该阶段。本轮没有修改此行为。

## 9. 文件保护与交付核对

本轮开始时保存 782 个已跟踪文件及两份原报告的 SHA-256，共 784 个受保护文件。交付前复核这些内容均未变化，`git diff` 与暂存区 diff 均为空；仅新增本报告，原有两份未跟踪报告保留原样。

仓库外的安装文件、用户环境备份、校验元数据和命令结果保存在当前用户软件目录/临时目录；没有向仓库添加安装器、密码、密钥、业务代码、POM、SQL 或补丁配置。Docker 用户插件目录新增的是既有官方 Compose 二进制副本。

初次修复阶段在此停止，当时留下系统 PATH、Docker Engine 和中间件决策事项；用户后续手动处理后的复验见下一节。没有开始业务开发。

## 10. 用户手动处理后的复验

复验时间：2026-11-16 14:49（Asia/Shanghai）。本次只读取环境、执行版本/状态命令及更新本报告，没有安装或升级软件、修改环境变量、启动中间件、运行项目编译或进行业务开发。

### 10.1 Java 和 Maven：原路径阻塞已消除

当前继承进程中的 `Get-Command java,javac,mvn` 已指向新装工具。另读取持久化的用户 JAVA_HOME/MAVEN_HOME，使用“系统 PATH + 用户 PATH”构造新登录路径后再次检查，结果一致；没有额外在进程 PATH 前置 JDK/Maven bin。

系统 PATH 已不再包含 Oracle Java javapath。两项用户变量仍为：

```text
JAVA_HOME=%USERPROFILE%\AppData\Local\Programs\Eclipse Adoptium\jdk-17.0.21+9
MAVEN_HOME=%USERPROFILE%\AppData\Local\Programs\Apache Maven\apache-maven-3.9.16
```

### 10.2 五项版本命令：全部成功

| 实际命令 | 最新执行结果 | 退出码 |
| --- | --- | --- |
| `java -version` | Temurin 17.0.21+9 | 0 |
| `javac -version` | javac 17.0.21 | 0 |
| `mvn -version` | Apache Maven 3.9.16；Java 17.0.21，vendor Eclipse Adoptium | 0 |
| `docker version` | Client 29.9.3 / Server Engine 29.9.3；Docker Desktop 4.99.0 (245822)；context desktop-linux | 0 |
| `docker compose version` | Docker Compose v5.7.0 | 0 |

Java/Maven 关键原始输出：

```text
openjdk version "17.0.21" 2026-10-20
OpenJDK Runtime Environment Temurin-17.0.21+9 (build 17.0.21+9)
OpenJDK 64-Bit Server VM Temurin-17.0.21+9 (build 17.0.21+9, mixed mode, sharing)

javac 17.0.21

Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
Maven home: %USERPROFILE%\AppData\Local\Programs\Apache Maven\apache-maven-3.9.16
Java version: 17.0.21, vendor: Eclipse Adoptium, runtime: %USERPROFILE%\AppData\Local\Programs\Eclipse Adoptium\jdk-17.0.21+9
Default locale: zh_CN, platform encoding: GBK
OS name: "windows 11", version: "10.0", arch: "amd64", family: "windows"
```

Docker 的 Client 和 Server 均返回版本信息，没有此前的命名管道连接错误。与初次修复记录相比，Docker Desktop / CLI / Compose 的实测版本已经变化；这是用户手动处理后观察到的状态，本次复验未执行软件升级。

`docker ps --all` 也退出码 0，只列出两个已停止的其他项目容器，未发现本项目中间件在运行。本次没有启动、停止、删除或清理任何容器/数据卷。

### 10.3 仍未完成的验收与下一步

对 127.0.0.1 和 ::1 分别探测 MySQL 3306、Redis 6379、RabbitMQ 5672/15672、Nacos 8848/9848，全部返回 `ECONNREFUSED`。因此 Docker 工具链可用不代表这些中间件已部署或业务应用已能启动。

以下事项保持待决策/待验证：

- MySQL 5.7 与 Connector/J 9.3.0 的兼容方案；实际 Server 版本与 schema 未读取。
- Nacos Server 2.1.0 与预期 Client 3.0.3 的默认 namespace 兼容性、9848 映射、auth 两个 DataId。
- RabbitMQ `/mall`、mall 用户及权限、真实队列/绑定/policy 初始化。
- 项目 Maven 编译、最终依赖解析和已有测试执行结果。

工具链阻塞已消除，下一阶段可优先执行下列编译命令；本次未执行：

```powershell
mvn -pl mall-portal -am -DskipTests=true test
```

该命令仍跳过测试执行，不代表测试通过；继续避免直接执行绑定远程镜像构建的 package 阶段。

本次复验仅更新本报告；782 个已跟踪文件和两份基线报告共 784 个受保护文件内容均未变化。到此停止，等待下一阶段指令。
