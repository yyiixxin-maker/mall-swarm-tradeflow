# TradeFlow 本机开发环境配置报告

日期：2026-11-16（Asia/Shanghai）。  
仓库提交：`04c442fe318356bae4445a109dde7af297ce1e84`。  
范围：本机后端开发环境及已有测试；保留原业务代码、POM、SQL 和配置文件。

## 1. 完成结果

**本机环境已配置并启动：7 个中间件容器全部 healthy，7 个应用全部 UP；9 个模块编译成功，Maven 默认发现的 9 个现有测试方法全部通过。**

已验证的实际链路包括：

- 网关 → 门户 → MySQL 的首页数据查询。
- 网关 → auth → admin → Redis 的登录与令牌访问。
- 搜索模块 → MySQL → Elasticsearch 的样例商品导入。
- admin → MinIO 的文件上传，以及下载后字节内容一致性。
- Nacos 配置读取、12 个 DataId 的 MD5 核对和 7 个健康服务实例。
- RabbitMQ 应用账号权限、持久化队列、绑定和 TTL/DLX 参数；取消队列有 1 个真实应用消费者。
- 一键启动重复执行成功，既有本地数据保留。

用户已明确选择“新建 MySQL 8.4，保留 Connector/J 9.3.0”。本轮使用全新的 Compose 项目、网络及命名数据卷，没有升级或覆盖既有数据库。

## 2. 版本与兼容性

| 组件 | 实际采用/解析版本 | 验证依据 |
| --- | --- | --- |
| JDK | Eclipse Temurin 17.0.21+9 | Maven 使用 Java 17，应用进程使用 JAVA_HOME 下的 java.exe |
| Maven | 3.9.16 | 真实 reactor 编译和测试成功 |
| Spring Boot / Cloud / Alibaba | 3.5.14 / 2025.0.2 / 2025.0.0.0 | 保持原 POM；依赖解析和实际运行 |
| MySQL Server | 8.4.13 | 实际 SELECT VERSION() |
| Connector/J | 9.3.0 | 实际 runtime classpath |
| Redis | 7.4.12 | 容器 redis-server --version |
| RabbitMQ Server / Java Client | 3.9.11 / 5.25.0 | 实际管理 API / runtime classpath |
| Spring AMQP | spring-rabbit 3.2.10 | 实际 runtime classpath |
| Nacos Server / Client | 3.0.3 / 3.0.3 | 部署镜像、实际依赖和服务注册 |
| MongoDB Server / Java Driver | 4.4.29 / 5.5.2 | 容器版本、实际依赖及门户健康检查 |
| Elasticsearch Server | 8.18.1，安装匹配版本 IK | 实际 HTTP 版本、中文分词及索引验证 |
| Spring Data Elasticsearch / Java Client | 5.5.11 / 8.18.8 | 实际 runtime classpath |
| MinIO | 官方 RELEASE.2025-04-22T22-12-26Z 对应固定源码，本地构建 | 源码 commit `0d7408fc9969caf07de6a8c3a84f9fbb10a6739e`，真实上传/下载验证 |

Connector/J 9.3.0 支持 MySQL Server 8.0 及之后版本；使用新 MySQL 8.4 避开了原始 Compose 5.7 的支持范围缺口。[官方驱动说明](https://dev.mysql.com/doc/relnotes/connector-j/en/news-9-3-0.html)

Spring Data Elasticsearch 2025.0 / 5.5.x 的官方对应服务器主线为 8.18.1；新环境使用该版本并安装同版本 IK，不改 Java 依赖。[Spring Data 版本表](https://docs.spring.io/spring-data/elasticsearch/reference/elasticsearch/versions.html)

MinIO 历史 Docker Hub/Quay 镜像匿名拉取失败，官方下载二进制返回 HTTP 410，因此使用官方已签名发布对应的固定源码构建。镜像保留 AGPL 许可证；没有使用来源不明镜像。[对应官方发布](https://github.com/minio/minio/releases/tag/RELEASE.2025-04-22T22-12-26Z)

## 3. 访问地址与启动入口

| 功能 | 地址 |
| --- | --- |
| 网关聚合 API 文档 | <http://127.0.0.1:18201/doc.html> |
| 后台 API 文档 | <http://127.0.0.1:18080/swagger-ui.html> |
| 门户 API 文档 | <http://127.0.0.1:18085/swagger-ui.html> |
| 搜索 / auth / demo | `127.0.0.1:18081` / `18401` / `18082` |
| 监控界面 | <http://127.0.0.1:18101> |
| Nacos 控制台 | <http://127.0.0.1:18880> |
| RabbitMQ 管理台 | <http://127.0.0.1:15673> |
| MinIO 控制台 | <http://127.0.0.1:19001> |

所有入口仅绑定本机回环地址。完整连接参数、用户名及口令项名称见 `environment/README.md`；口令仅保存在已被 Git 忽略的 `environment/.runtime/secrets.env`，报告不包含口令或令牌。

仓库根目录下执行：

```powershell
# 启动/恢复整个本地环境；已实际验证可重复执行
python environment/local.py up

# 检查全部中间件及应用
python environment/local.py status

# 停止本环境，保留数据卷
python environment/local.py stop

# 显式运行现有测试
python environment/local.py test
```

启动程序只使用 Python 标准库，需要本机 Java 17、Maven 3.9.x 和运行中的 Docker Desktop。应用日志、构建/测试结果、PID 与私密配置都保存在 `.runtime`，不进入 Git。

## 4. 数据库与存储

- MySQL 使用独立端口 `13306`，数据库 `mall`，应用用户 `mall`，独立新卷 `tradeflow-local_mysql-data`。
- 原 `document/sql/mall.sql` 未改动、未直接导入。启动程序从受支持的行式 Navicat dump 生成去除 DROP 语句的私有副本，并执行安全自检；只在新空卷首次启动时初始化。
- 已读取到 **76 张表、38 条商品记录**。另建 `tradeflow_local` 本地开发账号及对应角色，没有覆盖原有账号。
- 搜索索引 `pms` 已有 **20 条符合原 DAO 条件的商品记录**。原查询要求 `delete_status=0 AND publish_status=1`，因此与商品总表 38 条不同。
- Redis 使用 `16379` 和口令，避免占用其他项目的 `6379`。
- MongoDB 使用 `27018`，数据库/认证库为 `mall-port`，独立应用用户只获该库 readWrite 权限。
- MinIO 的 S3/控制台端口为 `19000` / `19001`，使用独立数据卷与 `mall` 桶。样例上传探针为本地验证文件，不含真实业务内容。

既有 `smart-travel-redis` 和 `opengauss` 容器保留，没有执行全局清理、删除卷、导入 DROP SQL 或覆盖旧数据目录。

## 5. RabbitMQ 初始化与实测

AMQP：`127.0.0.1:5673`；vhost：`/mall`。

管理账号 `bootstrap` 与应用账号 `mall` 分离；应用账号没有管理权限，其 configure/write/read 权限均限定为 `^mall\.order\..*`。

| 队列 | durable | 实际参数/状态 |
| --- | --- | --- |
| mall.order.cancel | true | 对应交换机 mall.order.direct，routing key mall.order.cancel；1 个应用消费者 |
| mall.order.cancel.ttl | true | 对应交换机 mall.order.direct.ttl；DLX=mall.order.direct，dead-letter-routing-key=mall.order.cancel |

两条队列的实测积压均为 0。沿用原消息 Long orderId 和逐消息 expiration 的延迟机制。初始化没有删除或清空队列。

独立的本地 Nacos 配置使用有效 `publisher-confirm-type=correlated`、publisher returns 属性；没有据此宣称交易可靠消息、Outbox 或发布确认回调业务已实现。

## 6. Nacos 配置与发现

- namespace：`tradeflow-local`。
- group：`TRADEFLOW_LOCAL`。
- SDK/HTTP：`127.0.0.1:18848`；客户端 gRPC：`127.0.0.1:19848`，保持主端口 +1000 映射。
- 管理员 `nacos` 用于初始化；应用使用单独的 `tradeflow` 用户，权限限此 namespace。
- admin、portal、search、auth、gateway、demo 各有 dev/prod 两个 DataId，共 **12 个**，逐项读取及 MD5 比对成功。
- 其中 `mall-auth-dev.yaml` 和 `mall-auth-prod.yaml` 已在此隔离 namespace 补齐。prod 名称也是本机样例，不能当成真实生产配置。
- 7 个应用均在该 namespace/group 注册，各有 **1 个健康实例**，注册地址为本机回环 IP 和专用应用端口。

原始配置文件仍保留旧地址/版本；启动进程通过独立配置和环境变量接入新环境。应用配置中心内容使用私密环境变量占位符，没有发布真实口令值。

## 7. 编译与测试证据

首先实际执行并成功：

```text
mvn -B -ntp -pl mall-portal -am -DskipTests=true test
```

随后实际执行全 reactor 编译与 runtime classpath 生成，全部成功：

```text
mvn -B -ntp -DskipTests=true test dependency:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt -DincludeScope=runtime
```

没有执行 package/install/deploy；原 POM 绑定 package 的远程 Docker 构建没有触发。本地 Elasticsearch/MinIO 镜像由 Docker CLI 独立构建。

测试通过启动脚本显式使用 `skipTests=false`，加载隔离配置并关闭测试上下文的 Nacos 服务注册和监控注册。实际结果：

| 模块 | 类数 | 执行方法 | 失败/错误/跳过 |
| --- | --- | --- | --- |
| demo | 1 | 2 | 0 / 0 / 0 |
| search | 1 | 3 | 0 / 0 / 0 |
| portal | 2 | 2 | 0 / 0 / 0 |
| monitor | 1 | 1 | 0 / 0 / 0 |
| gateway | 1 | 1 | 0 / 0 / 0 |
| 合计 | 6 | 9 | 0 / 0 / 0 |

admin 的另外 2 个方法仍位于 `src/test/com`，未被默认源集纳入；common、mbg、auth 未发现 Java 测试。没有把这些项目计作“测试通过”，也没有将普通跳过测试的编译记作测试成功。

新增环境程序的自检包含：去 DROP 后仍有 76 张表、拒绝不安全 SQL 语句、配置文档可解析、端口唯一、敏感输出脱敏。管理 Java PID 的归属核对还验证了本工作区进程可识别、非本工作区进程不匹配。

## 8. 文件保护与范围限制

开始前记录的 782 个已跟踪文件及三份原报告，共 **785 个文件**，内容 SHA-256 均未变化。没有修改业务代码、根/模块 POM、原 SQL 或原配置。

新增内容仅为 `environment/` 下的隔离 Compose、两个本地镜像 Dockerfile、启动/配置脚本、独立日志配置、使用说明及本报告。`.runtime` 内的口令、生成 SQL、日志、PID 与验证结果均由 Git 忽略。原始工作区权限继承已保留，没有修改系统权限或请求管理员提权。

以下内容不计作本轮已接入：

- 真实阿里云 OSS 与支付宝账户：未提供真实凭据。OSS 使用明确本地占位满足原代码的客户端构造；真正文件上传走 MinIO，OSS 接口不可用于真实上传。
- 商城前端：不在该后端仓库内。当前提供的是后端网关和 API 文档。
- Nginx/Logstash/Kibana：本机后端调试不依赖它们；应用采用独立控制台日志配置，未部署这些可选组件。
- 生产部署与交易正确性改造：RabbitMQ 3.9、MongoDB 4、已归档 MinIO 以及原代码的监控硬编码口令等仍需生产前评审。本机端口不对外暴露。
- 订单并发、支付可信边界、幂等/Outbox、故障恢复与压测：本轮没有实现或验收，继续遵循 BASELINE_AUDIT 的后续开发顺序。

本机后端环境保持运行。后续可直接开展业务修复与测试工作，无需再修改原始配置来接入这些本地中间件。
