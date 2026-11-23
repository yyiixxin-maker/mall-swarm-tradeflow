# mall-swarm 第一阶段环境检查报告

检查日期：2026-11-16（Asia/Shanghai）。  
依据：仓库现有 `BASELINE_AUDIT.md`，重点对应第 2、6、7、8 节及 D1 环境验收要求。  
当前目录：`.`。  
当前分支：`feature/tradeflow-mvp`。  
当前提交：`04c442fe318356bae4445a109dde7af297ce1e84`，与原审计固定提交一致。

## 1. 检查结论与证据边界

**环境基线尚未通过。指定 Maven 命令已实际尝试，但因当前终端找不到 `mvn`，未进入 Maven 生命周期；源码是否能编译、依赖最终解析版本和测试是否通过均未得到验证。**

当前还存在默认 Java 启动器异常、Docker 引擎不可连接、Compose 不可用及外部服务端口不可达等阻塞。静态检查确认 MySQL 驱动与服务器目标版本不在官方支持组合内、RabbitMQ 初始化资料不足、Nacos 默认 namespace 的客户端/服务端版本组合存在兼容性问题。

| 检查项 | 本次结果 | 证据性质 |
| --- | --- | --- |
| Java / Maven / Docker / Compose | 已检查；存在工具链阻塞 | 本机命令执行 |
| 根 POM 与 9 个模块 POM | 10 份 XML 可解析，已检查继承、直接依赖、排除项及插件 | 静态检查；未完成 Maven 模型构建 |
| Maven 最终依赖版本 | 未取得 effective POM / dependency tree | 命令启动失败；不能用版本声明替代实际解析结果 |
| MySQL 兼容性 | Connector/J 声明 9.3.0；Compose 目标 MySQL 5.7，不在该驱动官方支持范围内 | 仓库声明及官方资料；未复现 JDBC 连接 |
| Compose 配置 | 两份 YAML 可解析；Compose 自身校验未完成 | 通用 YAML 解析成功，Compose 命令失败 |
| RabbitMQ | 已核对配置和代码拓扑；运行时 vhost / 用户 / 权限 / 队列未知 | 管理端口不可达，未访问实际 broker |
| Nacos | 已盘点 DataId、namespace、group、服务名与网关发现配置；实际发布和注册状态未知 | HTTP / gRPC 端口不可达 |
| 编译 | 阻塞，尚未编译 | shell 退出码 1，未启动 Maven |
| 测试 | 本轮实际执行 0 个测试；结果未知 | 显式启用测试的命令同样无法启动 Maven |

本轮唯一新增的仓库交付文件是本报告。没有修改业务代码、POM、SQL、数据库、配置文件或原审计文件；没有安装/下载 JDK、Maven 或依赖，没有更改 PATH / JAVA_HOME，没有启动容器或业务应用，没有导入配置或执行 SQL。没有执行 `package`、`install`、`deploy` 或 Docker 镜像构建。

## 2. 环境版本

执行环境为 Windows / PowerShell；项目 POM 声明 Java 17。

| 项目 | 实测结果 | 说明 |
| --- | --- | --- |
| PATH 中的 `java` | 无法取得版本；原生进程退出码 `3221226505`，即 `0xC0000409`，无版本输出 | 入口为 `C:\Program Files\Common Files\Oracle\Java\javapath\java.exe` |
| PATH 中的 `javac` | 同样异常退出，无版本输出 | 不能视为可用编译器 |
| Java 注册表记录 | JDK 20 / 20.0.2，JavaHome 指向 `C:\Program Files\Java\jdk-20` | 该目录不存在；注册表记录不能作为实际已安装版本 |
| 已有其他 Java | PyCharm 内置 JBR `21.0.10+1-b1163.108`；绝对路径运行 `java -version` 成功，`javac -version` 为 `21.0.10` | 位于 `C:\Program Files\JetBrains\PyCharm 2025.3.3\jbr\bin`；未切换为项目构建 JDK，未验证其构建结果 |
| `JAVA_HOME` / `MAVEN_HOME` | 当前终端均未设置 | 未发现可确认的项目 JDK 17 工具链 |
| Maven | `mvn` 不在 PATH，`mvn -version` 无法执行；版本未知 | 仓库没有 Maven Wrapper；常见安装目录检索未找到 `mvn.cmd` / `mvn.bat`，用户 `.m2` 目录不存在 |
| Docker Client | `25.0.3`，build `4debf41`，API `1.44`，Windows amd64 | `docker --version` 成功 |
| Docker Server | 版本未知，无法连接 | `default` 的 `docker_engine` 和 `desktop-linux` 的 `dockerDesktopLinuxEngine` 命名管道均不存在 |
| Docker Compose 插件 | 无法取得版本 | `docker compose version` 返回 `docker: 'compose' is not a docker command` |
| 独立 `docker-compose` | 可找到 `.exe`，但不可用，版本未知 | `docker-compose --version` 同样返回上述 compose 不可用错误 |
| MySQL CLI | 当前 PATH 未找到 `mysql` | 未执行 `SELECT VERSION()` |

工具检索覆盖 PATH、Java 注册表、常见 Program Files / Local Programs / Chocolatey 等位置；部分 WindowsApps 子目录拒绝访问，因此不声称已穷尽全盘安装。已有 JBR 21 可运行不代表当前 `java` 命令已修复，也不代表 Java 17 基线已经满足。

`docker version` 的关键输出为 `Server: null`，错误包含：

```text
open //./pipe/docker_engine: The system cannot find the file specified.
open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified.
```

这是本次连接结果；不能据此判断远端或其他机器上是否另有运行环境。本轮没有尝试连接 POM 的远程 Docker `192.168.3.101:2375`。

## 3. Maven 依赖检查与实际版本状态

### 3.1 根 POM

根 POM 是 `com.macro.mall:mall-swarm:1.0-SNAPSHOT`，packaging 为 `pom`，包含 9 个子模块；所有模块均继承该父工程。10 份 POM 经 XML 解析均有效，但 XML 有效不等于父 POM、BOM 和依赖能被 Maven 成功解析。

| 项目/依赖 | 本地 POM 声明或管理版本 | 本轮实际解析结果 |
| --- | --- | --- |
| Java | 17 | 未执行 Maven 编译 |
| Spring Boot parent / plugin | 3.5.14 | 未解析 |
| Spring Cloud BOM | 2025.0.2 | 未解析 |
| Spring Cloud Alibaba BOM | 2025.0.0.0 | 未解析 |
| MySQL Connector/J，`com.mysql:mysql-connector-j` | 9.3.0 | 未下载/加载实际 JAR |
| MyBatis / Boot Starter | 3.5.19 / 3.0.4 | 未解析 |
| Druid Boot 3 Starter | 1.2.24 | 未解析 |
| PageHelper / Starter | 6.1.1 / 2.1.1 | 未解析 |
| MyBatis Generator | 1.4.2 | 未解析 |
| Spring Data Commons | 3.5.11，根 POM 显式管理 | 未解析；需在依赖树中核对与其余 Spring Data 组件的一致性 |
| Hutool | 5.8.40 | 未解析 |
| 阿里云 OSS / MinIO | 3.18.5 / 8.6.0 | 未解析 |
| 支付宝 SDK | 4.40.630.ALL | 未解析 |
| Logstash Logback Encoder | 8.0 | 未解析 |
| Spring Boot Admin | 3.5.6 | 未解析 |
| Knife4j / Springdoc | 4.5.0 / 2.8.17 | 未解析 |
| Sa-Token | 1.42.0 | 未解析 |
| Commons Lang3 | 3.20.0 | 未解析 |
| 内部 mall-common / mall-mbg | 1.0-SNAPSHOT | 未构建 |
| Fabric8 Docker Maven Plugin | 0.45.1 | 未执行 |
| Spring AMQP / RabbitMQ Java Client / Spring Data Redis / Lettuce / JUnit / Mockito / Lombok 等 | 依赖父 POM / BOM 或传递依赖管理，模块没有逐项固定版本 | 未取得依赖树，实际版本未知 |
| Nacos Client | 本地 POM 未单独固定；Alibaba 2025.0.0.0 官方组件表对应 3.0.3 | 这是上游预期版本，本机最终解析版本仍未知 |

上表刻意保留“未解析”：本轮没有可以证明最终运行依赖版本的 Maven 输出或 JAR。Alibaba / Boot / Cloud 所属主线可从[官方版本关系](https://sca.aliyun.com/docs/2025.x/overview/version-explain/)核对；这不能替代本项目的依赖解析或启动验证。

根 POM 还存在以下影响验收的行为：

- `pom.xml:34` 默认 `skipTests=true`。这通常跳过 Surefire 测试执行，仍会经过测试源码编译；它不是 `maven.test.skip=true`。即使指定的构建命令将来成功，也只能证明编译及相关生命周期成功，不能声称测试通过。
- 根 POM 的 `<dependencies>` 包含 Actuator、AOP、starter-test（test scope）、Hutool、Lombok 等，会被子模块继承。
- Docker 插件在 `pom.xml:285` 绑定 `package`，使用 `http://192.168.3.101:2375`。admin、search、portal、monitor、gateway、auth 启用了该插件；本轮只尝试 `test` 生命周期和只读 Maven goals。
- Maven 仓库和插件仓库包含阿里云 public 与 `repo1.maven.org`。由于 Maven 未能启动，未验证这些仓库的解析、下载或镜像覆盖情况，也没有漏洞扫描结果。

### 3.2 各模块依赖范围

以下为各模块直接依赖的主要范围，须叠加根依赖和传递依赖，不能理解为已解析的完整 classpath。

| 模块 | 工程内依赖与主要外部依赖 | 已核对的特殊点 |
| --- | --- | --- |
| mall-common | Web、Redis、PageHelper、Spring Data Commons、Knife4j/Springdoc、日志、Validation、LoadBalancer | Knife4j 的 Springdoc 排除/手工固定来自根管理 |
| mall-mbg | mall-common；PageHelper Starter、MyBatis Starter、Druid、Generator、Connector/J | 数据库驱动由本模块传入上层服务 |
| mall-admin | mall-mbg；Nacos discovery/config、Redis、OSS、MinIO、OpenFeign、Sa-Token、commons-pool2、监控客户端 | 测试源目录问题见第 8 节 |
| mall-portal | mall-mbg；MongoDB、Redis、AMQP、Nacos discovery/config、OpenFeign、支付宝 SDK、Sa-Token、commons-pool2、监控客户端 | 指定编译命令覆盖的主要业务模块 |
| mall-search | mall-mbg；Web、Elasticsearch、Nacos discovery/config、OpenFeign、监控客户端 | 对 mall-mbg 排除 `spring-boot-starter-data-redis` |
| mall-demo | mall-mbg；Web、OpenFeign、Nacos discovery/config、监控客户端 | 有 Spring Boot 测试；未启用 Docker Maven Plugin |
| mall-auth | mall-common；Web、Nacos discovery/config、OpenFeign、监控客户端 | 对 mall-common 排除 Redis；Nacos 配置快照缺失 |
| mall-gateway | mall-common；Gateway Server WebFlux、Nacos discovery/config、Knife4j Gateway、Redis、Sa-Token Reactor、commons-pool2、监控客户端 | 排除 mall-common 带入的 Web/Redis starter，再显式引入所需 Redis，避免把该排除误报为漏依赖 |
| mall-monitor | Web、Nacos discovery、Boot Admin Server、Security | 没有 Nacos config starter，也没有配置中心 DataId 导入 |

指定 `-pl mall-portal -am` 的预期 reactor 范围为根工程、mall-common、mall-mbg、mall-portal。其成功也不能代替其余 6 个模块的编译。本轮补充尝试了全 reactor 的 `test` 编译命令，但同样在 Maven 启动前阻塞。

## 4. 数据库兼容性问题

| 项目 | 已确认事实 | 尚未确认 |
| --- | --- | --- |
| JDBC 驱动 | 根 POM 管理 Connector/J 9.3.0；mall-mbg 依赖该驱动 | 实际依赖解析/JAR 版本、连接与认证是否成功 |
| Compose 目标 | `document/docker/docker-compose-env.yml:4` 为 `mysql:5.7` | 具体镜像 digest、补丁版本、运行版本 |
| SQL 来源 | `document/sql/mall.sql:6` 与 `:11` 均为 50719，即导出来源/目标标记 5.7.19 | 不能据此认定当前服务器就是 5.7.19 |
| 应用目标 | portal dev 为 `localhost:3306/mall`，prod 快照为 `db:3306/mall` | 实际 Nacos 是否覆盖这些地址 |
| 本机连接 | localhost、127.0.0.1、::1 的 3306 均拒绝连接 | 未执行任何 SQL，包括 `SELECT VERSION()` |

**兼容性缺口已确认于声明层面：MySQL 官方说明 Connector/J 9.3.0 用于 MySQL Server 8.0 及之后版本；Compose 的 5.7 目标不属于该支持组合。** 这不等于已复现连接失败或证明一定不能握手。依据：[Connector/J 9.3.0 官方发布说明](https://dev.mysql.com/doc/relnotes/connector-j/en/news-9-3-0.html)。

另外，MySQL Compose 只设置 root 密码、字符集、端口和宿主机卷，没有 `MYSQL_DATABASE=mall`，也没有挂载建库初始化 SQL。空数据卷首次启动不能保证存在 `mall` 库和业务表；旧卷可能已包含它们，当前无法核验。

下一阶段应先读取实际服务器版本、认证方式、字符集与 schema，再评审服务器/驱动兼容方案；本轮没有升级服务器、降级驱动或修改任何版本。仓库 SQL 含 `DROP TABLE IF EXISTS`，不能把它当作对既有数据库无损的环境检查命令直接导入。

## 5. Docker Compose 与外部服务

### 5.1 静态配置

| 服务 | Compose 声明 | 配置核对与问题 |
| --- | --- | --- |
| MySQL | `mysql:5.7`，`3306:3306`，utf8mb4 / utf8mb4_unicode_ci，数据/配置/日志卷 | 驱动兼容缺口；缺少 `mall` 库初始化；root 为明文示例口令配置 |
| Redis | `redis:7`，`6379:6379`，`redis-server --appendonly yes`，`/data` 卷 | Nacos 的 admin/gateway/portal 快照均为空 Redis 密码，与 Compose 未配置 requirepass 静态一致；实际鉴权、AOF、补丁版本未知 |
| RabbitMQ | `rabbitmq:3.9.11-management`，5672 / 15672，数据/日志卷 | 没有声明应用用户、vhost、权限或 definitions 导入；详见第 6 节 |
| Nacos | `nacos/nacos-server:v2.1.0`，`MODE=standalone`，只映射 8848 | 未映射客户端 gRPC 9848；没有显式数据卷或配置导入；默认 namespace 版本兼容问题见第 7 节 |

两份文件经现有 PyYAML 解析：环境 Compose 为 9 个服务，应用 Compose 为 6 个服务，YAML 语法有效。`docker compose -f ... config --quiet` 实际失败，提示 `unknown shorthand flag: 'f' in -f`；结合版本命令可知 Compose 入口不可用，**没有取得 Compose schema 校验成功的结果**。

共同限制：

- 两份 Compose 没有显式共同网络，也没有 healthcheck。应用侧使用 `external_links`，能否解析 `db`、`rabbit`、`redis`、`nacos-registry` 取决于实际 Compose 项目和共同网络，不能只看别名就判定已连通。
- 应用 Compose 没有基础设施就绪门槛。服务启动顺序和依赖就绪需要另行复验。
- 卷路径按 Linux `/mydata/...`、`/etc/localtime` 编写；本机为 Windows，路径、共享与既有卷内容没有经过容器运行验证。
- 端口映射没有指定只绑定回环地址，Redis 未配置口令；用于非隔离环境前须核对访问边界。当前没有启动这些映射。
- README 的 RabbitMQ 3.10.5 与 Compose 3.9.11 声明不同；运行镜像版本未知。`redis:7`、`mysql:5.7` 也不能代表固定补丁版本或 digest。

### 5.2 实际端口探测

对 `localhost`、`127.0.0.1`、`::1` 分别进行了 TCP 探测，以下各端口均返回 `ECONNREFUSED`：

| 服务 | 端口 | 对本阶段检查的影响 |
| --- | --- | --- |
| MySQL | 3306 | 无法读取服务器版本或校验 JDBC 连接 |
| Redis | 6379 | 无法取得 INFO、鉴权和持久化实测状态 |
| RabbitMQ | 5672 / 15672 | 无法校验 AMQP 认证、用户权限或管理拓扑 |
| Nacos | 8848 / 9848 | 无法读取已发布配置、namespace、服务注册或验证 gRPC |
| MongoDB | 27017 | portal Spring 上下文相关外部依赖不可达 |
| Elasticsearch | 9200 | search 模块集成测试相关外部依赖不可达 |

此结果限定于本机配置目标和探测时刻，不代表其他环境不存在服务。生产快照中的 Docker 内部别名没有在可用容器网络中验证。

## 6. RabbitMQ 配置问题

### 6.1 应用要求与初始化缺口

portal 基础配置、dev/prod Nacos 快照均要求 **vhost `/mall`、用户 `mall`**；dev 地址为 localhost，prod 快照地址为 rabbit，端口均为 5672，口令有配置但本报告不复制。

环境 Compose 没有 `RABBITMQ_DEFAULT_USER`、`RABBITMQ_DEFAULT_PASS`、`RABBITMQ_DEFAULT_VHOST`，没有 definitions 文件/加载配置，也未找到创建 vhost、用户和授权的初始化脚本。应用中的队列 Bean 不能代替 broker 上的用户/vhost 初始化。

因此结论是：**仓库缺少新环境所需初始化保证；本机 broker 不可访问，实际是否已手工创建这些对象未知。** 不能把“未核验”写成“实际一定不存在”。

### 6.2 代码声明的队列与绑定

证据：`RabbitMqConfig.java`、`QueueEnum.java`、`CancelOrderSender.java`、`CancelOrderReceiver.java`，均位于 mall-portal。

| Exchange | 类型/持久化 | Queue | Routing key | 队列参数 |
| --- | --- | --- | --- | --- |
| `mall.order.direct.ttl` | Direct，durable=true | `mall.order.cancel.ttl`，durable=true | `mall.order.cancel.ttl` | DLX=`mall.order.direct`；dead-letter-routing-key=`mall.order.cancel` |
| `mall.order.direct` | Direct，durable=true | `mall.order.cancel`，由 `new Queue(name)` 声明 | `mall.order.cancel` | 代码未指定失败隔离 DLX 或重试上限 |

`new Queue(name)` 的标准默认是持久化、非 exclusive、非 auto-delete；这是代码/库行为层面的预期，没有读取 broker 验证。延迟由发送者对每条消息设置 `expiration=delayTimes`；代码没有声明队列级 `x-message-ttl`，也不依赖延迟消息插件。监听队列为 `mall.order.cancel`，消息体为 Long 类型 orderId。

配置问题：

- `application.yml:68–71` 的 `rabbitmq.queue.name.cancelOrder=cancelOrderQueue` 是非 spring 前缀自定义键；仓库检索只找到该定义，没有对应读取。它不是本次核对出的实际监听队列名。
- `config/portal/mall-portal-prod.yaml:22` 使用旧键 `spring.rabbitmq.publisher-confirms: true`。Boot 3.5 文档使用 `publisher-confirm-type`，不能把旧布尔键视为当前发布确认已启用。dev 也没有相应显式设置。依据：[Spring Boot 3.5 RabbitMQ 属性](https://docs.spring.io/spring-boot/3.5/appendix/application-properties/)。
- 未发现 publisher return / mandatory、confirm/return 回调以及消费重试上限/失败隔离的显式实现。实际 Nacos、环境变量或 broker policy 是否另有覆盖未知。
- 未显式配置手动 ACK；按 Spring AMQP 默认行为推断为 AUTO，未实测生效模式，也未验证消息到期转发、异常重投、积压及多实例消费者行为。

### 6.3 尚待运行时只读核验

由于本地管理端口和 Docker 引擎均不可达，本轮没有执行下列 broker 读操作。待有可用、已授权环境后应读取：

```text
docker exec rabbitmq rabbitmqctl list_vhosts name
docker exec rabbitmq rabbitmqctl list_users
docker exec rabbitmq rabbitmqctl list_user_permissions mall
docker exec rabbitmq rabbitmqctl list_permissions -p /mall
docker exec rabbitmq rabbitmqctl list_exchanges -p /mall name type durable
docker exec rabbitmq rabbitmqctl list_queues -p /mall name durable arguments messages consumers
docker exec rabbitmq rabbitmqctl list_bindings -p /mall
docker exec rabbitmq rabbitmqctl list_policies -p /mall
```

需要确认 `mall` 对预期交换机/队列具有所需 configure / write / read 权限、真实参数与声明一致、没有被 policy 覆盖。已有队列参数若不同，不能通过删除含消息队列来“修复检查结果”。

## 7. Nacos 配置问题

### 7.1 DataId 清单

7 个服务的基础 `application.yml` 均默认激活 dev。admin、auth、demo、gateway、portal、search 使用 `spring.config.import`；扩展名为 **`.yaml`**，导入均带 `refreshEnabled=true`，没有 `optional:` 前缀。

| 服务 | dev DataId | prod DataId | 仓库快照 |
| --- | --- | --- | --- |
| mall-admin | `mall-admin-dev.yaml` | `mall-admin-prod.yaml` | `config/admin/` 两份存在 |
| mall-auth | `mall-auth-dev.yaml` | `mall-auth-prod.yaml` | 两份均缺失，未发现 `config/auth/` |
| mall-demo | `mall-demo-dev.yaml` | `mall-demo-prod.yaml` | `config/demo/` 两份存在 |
| mall-gateway | `mall-gateway-dev.yaml` | `mall-gateway-prod.yaml` | `config/gateway/` 两份存在 |
| mall-portal | `mall-portal-dev.yaml` | `mall-portal-prod.yaml` | `config/portal/` 两份存在 |
| mall-search | `mall-search-dev.yaml` | `mall-search-prod.yaml` | `config/search/` 两份存在 |
| mall-monitor | 无显式 config 导入 | 无显式 config 导入 | 仅使用 Nacos discovery，不把没有 DataId 视为漏交付 |

12 个显式导入 DataId 中，有 10 份仓库样例；auth 的 2 份缺失是交付资料缺口，不能据此断言实际 Nacos 也不存在。`config/` 目录不是自动发布到 Nacos 的证据，Compose 也没有这些文件的导入动作。

### 7.2 地址、namespace、group 与服务发现

| 项目 | 仓库状态 | 结论 |
| --- | --- | --- |
| 配置/发现地址 | 六个配置中心客户端的 dev 为 `localhost:8848`，prod 为 `nacos-registry:8848`；monitor discovery 分别带 `http://` 前缀 | dev/prod 依赖不同网络位置；实际生效地址未取得 |
| config namespace / group | 未显式声明，import 查询参数也未指定 group | 预期使用默认 public namespace / `DEFAULT_GROUP`，属于静态推断 |
| discovery namespace / group | 未显式声明 | 需与网关及调用服务处于同一预期 namespace/group；实际值未知 |
| dev/prod 隔离 | DataId 名称区分环境，但服务名相同、未配置 namespace/group 隔离 | 如果两套服务接入同一 Nacos，存在发现到另一环境实例的风险 |
| 服务名 | mall-admin、mall-auth、mall-demo、mall-gateway、mall-monitor、mall-portal、mall-search | 与各自 `spring.application.name` 一致 |
| 网关 | discovery locator enabled=true、lower-case-service-id=true；显式 `lb://mall-auth`、`mall-admin`、`mall-portal`、`mall-search`、`mall-demo` | 静态目标名匹配；未验证健康实例、注册地址、负载均衡与实际路由 |
| 认证 | 仓库连接配置未显式提供 Nacos 用户/密码或密钥 | 目标服务若开启认证需核对有效注入；不能推断实际服务关闭认证 |

namespace/group 的默认行为依据：[Alibaba 2025.x Nacos 进阶指南](https://sca.aliyun.com/docs/2025.x/user-guide/nacos/advanced-guide/)。应用中的 Springdoc `group` 和 Kubernetes `namespace: default` 不是 Nacos group/namespace 配置。

### 7.3 必须处理的兼容性与端口问题

1. **默认 namespace 版本兼容性。** 当前 Alibaba BOM 为 2025.0.0.0；官方组件表对应 Nacos Client 3.0.3，而 Compose 目标为 Nacos Server 2.1.0。官方说明 2025.0.x 在 public/空字符串 namespace 场景需使用 Nacos Server 3.x；Nacos 3.0 SDK 文档也说明默认 namespace ID 从空字符串变为 `public`，涉及对旧服务端的配置中心兼容性。仓库没有显式 namespace，因此这是现有声明组合的实质问题，不只是版本较旧。本机最终依赖和运行时覆盖仍待 Maven/服务可用后确认。依据：[Alibaba 快速开始的重大变化说明](https://sca.aliyun.com/docs/2025.x/user-guide/nacos/quick-start/)、[Nacos 3.0 Java SDK 升级兼容性](https://nacos.io/docs/v3.0/manual/user/java-sdk/usage/)。本轮没有调整任何版本或 namespace。
2. **缺少客户端 gRPC 映射。** Compose 只发布 8848，没有 9848。官方说明客户端按主端口 +1000 访问 gRPC；宿主机 dev 客户端通过端口映射访问时，仅有 8848 不足。容器内直连是否可用需另查共同网络；9849 是服务端间通信端口，当前 standalone 场景不能与客户端 9848 混为一谈。依据：[Nacos 端口说明](https://www.nacos.io/en/docs/v2.5/upgrading/200-compatibility/)。
3. **状态持久化及交付不完整。** Nacos Compose 未声明显式数据卷或外部数据库，也未导入配置。容器重建后的配置恢复方式没有得到保证；auth 快照还需从实际环境读取并脱敏。

本轮 8848 / 9848 均拒绝连接，未取得真实 namespace ID、group、DataId 存在性、配置内容/MD5、服务端版本、注册实例或健康状态，也没有执行任何发布/注册操作。恢复环境后须分别核验“配置能读取”和“服务能发现”，不能只以控制台可打开作为成功依据。

## 8. 编译与已有测试执行结果

### 8.1 已实际尝试的命令

以下命令均在仓库根目录执行；前五项均返回 shell 退出码 **1**，原因是 `mvn` 命令不存在，而非 Maven 返回了源码编译错误。

| 顺序/目的 | 命令 | 实际结果 |
| --- | --- | --- |
| 首先尝试，按用户指定 | `mvn -pl mall-portal -am -DskipTests=true test` | 未进入 Maven；没有 compile/testCompile 结果 |
| 补充检查整个项目 | `mvn -DskipTests=true test` | 同样阻塞；其余模块也没有编译结果 |
| 显式启用已有 portal 测试 | `mvn -pl mall-portal -am -DskipTests=false test` | 同样阻塞；没有测试执行 |
| 实际依赖版本 | `mvn -DskipTests=true dependency:tree` | 同样阻塞；没有最终依赖树 |
| 有效模型 | `mvn -DskipTests=true help:effective-pom` | 同样阻塞；没有 effective POM |

最先执行命令的可读错误摘要：

```text
mvn: The term 'mvn' is not recognized as a name of a cmdlet,
function, script file, or executable program.
```

没有 `BUILD SUCCESS`、Maven reactor summary 或 Surefire 统计；检查中没有发现各模块 `target` 构建目录。**编译状态是“环境阻塞，未完成验证”，测试状态是“未执行”，不能写成源码已失败、测试已通过或全部 skipped。**

### 8.2 现有测试盘点

| 模块/测试类 | `@Test` 数 | 内容及依赖 | 本轮结果 |
| --- | --- | --- | --- |
| portal / MallPortalApplicationTests | 1 | Spring Boot `contextLoads`，仍需应用上下文及外部配置 | 未执行 |
| portal / PortalProductDaoTests | 1 | 查询商品 26/27/28/29，断言返回 4 个，依赖真实数据库固定数据 | 未执行 |
| search / MallSearchApplicationTests | 3 | 上下文、查商品、创建/写入 ES mapping | 未执行；mapping 测试有外部写入，后续应使用隔离 ES |
| demo / MallDemoApplicationTests | 2 | 上下文、日志序列化输出 | 未执行 |
| gateway / MallGatewayApplicationTests | 1 | Spring Boot 上下文 | 未执行 |
| monitor / MallMonitorApplicationTests | 1 | Spring Boot 上下文 | 未执行 |
| admin / PmsDaoTests | 2 | 商品 DAO 查询、带事务/回滚注解的批量插入 | 位于 `src/test/com/...`，不在默认测试源集 |
| common / mbg / auth | 0 | 未发现仓库 Java 测试类 | 无本轮测试执行结果 |

总计 **7 个类、11 个方法**。Maven 默认 `src/test/java` 下是 **6 个类、9 个方法**；admin POM 未自定义 `testSourceDirectory`，其 2 个方法在默认构建中不会自动纳入。指定 portal reactor 内是 2 个测试类、2 个方法。

未发现独立测试资源/profile 或隔离环境配置。多个测试加载完整 Spring 上下文，Nacos 配置不可用会影响启动；数据库、消息等依赖也不可达。本轮未执行任何可写外部系统的测试。

现有测试没有覆盖订单创建、库存并发、支付验签/金额核对、重复回调、支付与取消竞态、RabbitMQ 断连/重投或 Outbox。没有可报告的通过率、覆盖率、并发正确性或交易可靠性结论。

## 9. 必须先解决的问题

以下均为后续工作项，本阶段没有实施修复。

| 优先顺序 | 问题 | 继续验收前应取得的证据 |
| --- | --- | --- |
| 1 | 默认 Java 异常、Maven 缺失 | 恢复符合项目 Java 17 基线的可用构建 JDK 与 Maven；记录 `java/javac/mvn -version`，确认 Maven 实际使用的 JavaHome；保持现有 Boot/Cloud 主线 |
| 2 | Docker 引擎与 Compose 不可用 | 选定目标运行环境，取得 Server/Compose 版本、Compose config 校验及容器状态；检查 Windows/Linux 挂载和共同网络 |
| 3 | MySQL 9.3.0 驱动与目标 5.7 支持范围不匹配 | 读取真实 Server 版本并评审受支持的服务器/驱动方案；确认隔离 `mall` schema，不能直接覆盖既有数据 |
| 4 | Nacos Client / Server 默认 namespace 兼容性及 9848 映射 | 确认最终依赖与目标 Server 的支持组合；分别验证配置读取和服务注册/发现及 gRPC 可达性 |
| 5 | Nacos DataId、namespace/group 无运行证据，auth 快照缺失 | 核验 12 个导入 DataId 的实际存在性及脱敏内容、config/discovery namespace/group；补可恢复的配置交付资料 |
| 6 | RabbitMQ `/mall`、mall 用户/权限缺少初始化保证 | 取得 broker 版本及 vhost、用户、configure/write/read、队列/绑定/policy 的只读输出，核对声明一致性 |
| 7 | 旧 publisher-confirms 配置不能证明发布确认启用 | 核对 Boot 3.5 有效属性与实际发布确认/return 机制；变更需保留已有消息和队列兼容性 |
| 8 | 编译/测试无实测成功记录，admin 默认不纳入测试 | 先完成 portal reactor 与全 reactor 编译，再在隔离依赖下显式启用测试；单列 admin 源目录问题和各模块执行数 |

## 10. 下一阶段开发建议

先完成环境复验，再进入交易代码修改。建议延续原审计的 Java 17、Boot 3.5.14、Cloud 2025.0.2、Alibaba 2025.0.0.0 声明基线，针对已经确认的服务器兼容性与初始化问题分别做明确决策，不以任意升级整个技术栈代替诊断。

环境具备后，先复跑本报告第 8.1 节的 portal 编译命令、全 reactor 编译命令和 Maven 只读依赖 goals，保存最终依赖树；这些编译步骤无需将业务服务启动成功作为前提。随后在隔离 MySQL、Redis、RabbitMQ、Nacos、MongoDB 及需要时的 ES 中，核对有效配置并执行 `-DskipTests=false` 的测试。注意根 `skipTests=true` 与 admin 测试目录的两个独立问题，不能用默认构建成功代替测试验收。

完成上述复验并经确认后，再按 `BASELINE_AUDIT.md` 的优先级处理支付可信边界、订单/库存条件更新与支付取消并发、数据库与 MQ 双写等问题；新增幂等、支付记录或 Outbox 表的方案应基于已确认的数据库版本评审。现有队列参数及消息体兼容性必须保留；本报告没有实施任何这类开发。

本阶段到此停止，等待用户确认后再开展下一阶段。

## 11. 文件保护与复核记录

开始检查时工作区只有既有未跟踪文件 `BASELINE_AUDIT.md`。检查期间在仓库外临时目录保存命令摘要及 SHA-256 清单，保护范围为 782 个已跟踪文件和原审计文件，共 783 个文件；这些临时资料不作为额外仓库交付物。

交付前复核：上述 783 个文件的内容哈希均未变化；`git diff` 为空；工作区仅保留原有 `BASELINE_AUDIT.md` 并新增 `BASELINE_ENV_REPORT.md`。没有创建测试脚本、补丁配置、依赖缓存或构建产物。
