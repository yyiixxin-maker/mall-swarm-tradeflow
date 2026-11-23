# TradeFlow 本机开发环境

这套环境使用独立的 `tradeflow-local` Compose 项目、新数据卷和回环端口。原有 POM、数据库 dump 和原 Docker Compose 保留；本轮业务和安全配置修改见 `TRADEFLOW_RELIABILITY_REPORT.md`。应用在 Windows 上使用 JDK 17 运行，中间件在 Docker Desktop 中运行。

## 启动、检查和停止

在仓库根目录的 PowerShell 中执行：

```powershell
python environment/local.py up
python environment/local.py status
```

`up` 会准备私密配置、启动中间件、初始化 RabbitMQ/Nacos、执行增量交易表迁移、编译所有模块、启动 7 个应用，并验证登录、搜索和文件上传。首次需要下载镜像、Maven/Go 依赖并构建 MinIO，耗时取决于网络。后续执行保留现有数据及运行中的健康应用。

停止本环境，保留容器和数据卷：

```powershell
python environment/local.py stop
```

这不会停止其他项目的容器或 Java 进程；Java 进程按本工作区的进程标记核对。容器归属其他 checkout 时，脚本拒绝修改它们。不要使用删除卷的清理命令来重置环境。

## 访问地址

| 服务 | 地址 |
| --- | --- |
| 网关 / 聚合 API 文档 | <http://127.0.0.1:18201/doc.html> |
| 后台 API 文档 | <http://127.0.0.1:18080/swagger-ui.html> |
| 门户 API 文档 | <http://127.0.0.1:18085/swagger-ui.html> |
| 搜索 API | <http://127.0.0.1:18081> |
| 认证 API | <http://127.0.0.1:18401> |
| demo API | <http://127.0.0.1:18082> |
| Spring Boot Admin 监控 | <http://127.0.0.1:18101> |
| Nacos 控制台 | <http://127.0.0.1:18880> |
| RabbitMQ 管理台 | <http://127.0.0.1:15673> |
| MinIO 控制台 | <http://127.0.0.1:19001> |

## 账号与私密文件

口令仅保存在本机 `environment/.runtime/secrets.env`，该目录已被 Git 忽略。用本机编辑器查看所需项，勿将文件内容发送到聊天或提交到 Git。

| 用途 | 用户名 | 对应口令项 |
| --- | --- | --- |
| 商城本地开发管理员 | `tradeflow_local` | `TRADEFLOW_ADMIN_PASSWORD` |
| Nacos 控制台管理员 | `nacos` | `TRADEFLOW_NACOS_PASSWORD` |
| Nacos 应用账号 | `tradeflow`，限本地 namespace | `TRADEFLOW_NACOS_APP_PASSWORD` |
| RabbitMQ 管理台 | `bootstrap` | `TRADEFLOW_RABBIT_ADMIN_PASSWORD` |
| RabbitMQ 应用连接 | `mall`，无管理权限 | `TRADEFLOW_RABBIT_PASSWORD` |
| MinIO | `tradeflow` | `TRADEFLOW_MINIO_PASSWORD` |
| MySQL 应用连接 | `mall` | `TRADEFLOW_MYSQL_PASSWORD` |
| MongoDB 应用连接 | `mall` | `TRADEFLOW_MONGO_PASSWORD` |
| 监控界面 | 见 `TRADEFLOW_MONITOR_USER` | `TRADEFLOW_MONITOR_PASSWORD` |

监控服务的账号口令在原 Java 代码中写死，当前沿用并只绑定本机；真正轮换需要后续修改安全配置，不能声称已通过环境变量轮换。

认证入口为 `POST http://127.0.0.1:18201/mall-auth/auth/login`，表单字段是 `clientId=admin-app`、`username`、`password`。启动脚本已验证此登录链路及令牌访问，输出不会包含口令或令牌。

## 中间件连接参数

| 服务 | 本机连接参数 |
| --- | --- |
| MySQL 8.4 | `127.0.0.1:13306`，数据库 `mall` |
| Redis 7 | `127.0.0.1:16379`，数据库 0，口令项 `TRADEFLOW_REDIS_PASSWORD` |
| RabbitMQ 3.9.11 | AMQP `127.0.0.1:5673`，vhost `/mall` |
| Nacos 3.0.3 | SDK/HTTP `127.0.0.1:18848`，gRPC `127.0.0.1:19848` |
| MongoDB 4 | `127.0.0.1:27018`，数据库及认证库 `mall-port` |
| Elasticsearch 8.18.1 + IK | <http://127.0.0.1:19200>，商品索引 `pms` |
| MinIO S3 | <http://127.0.0.1:19000>，桶 `mall` |

Nacos 使用 namespace **`tradeflow-local`**、group **`TRADEFLOW_LOCAL`**。6 个配置中心客户端各有 dev/prod 两个 DataId，共 12 个，包含缺失的 auth 两项；这里的 prod 名称也只是此隔离 namespace 的本机样例，不是生产配置。应用凭据由启动进程环境注入，Nacos 配置中保存占位符。

RabbitMQ 原有两条持久队列：`mall.order.cancel` 和 `mall.order.cancel.ttl`，对应 Direct 交换机、routing key 及 TTL 队列的死信转发参数已初始化。应用用户只获 `mall.order.*` 资源所需权限。portal 另外声明 `mall.order.cancel.failed` 持久失败队列，有限重试后隔离消息，不更改原有队列参数。

MySQL 使用从原始 dump 生成的无 DROP 语句副本，只在新空卷首次启动时初始化 76 张表。原 SQL 从未直接导入或修改。重复启动不会重新初始化已有数据库。交易阶段再通过增量迁移添加 3 张可靠性表和 3 个索引，不改动原有数据行。脚本另建本地开发账号并将样例商品导入新搜索索引。

## 编译、测试和日志

```powershell
python environment/local.py build
python environment/local.py test
python environment/local.py self-test
```

- `build` 使用 test 生命周期并跳过测试执行，生成启动所需 classpath，不进入 package 阶段。
- `test` 显式设置 `skipTests=false`，使用隔离中间件，关闭测试上下文的服务注册和监控注册。
- admin 原有测试已移至 `src/test/java`。原有测试与新增交易用例的实际结果见 `TRADEFLOW_RELIABILITY_REPORT.md`；数据库和 RabbitMQ 故障测试必须通过下面的隔离入口运行。
- 应用日志、构建/测试日志、PID 和私密配置位于 `environment/.runtime/`。共享日志前必须脱敏。

启动只需要已配置的 Java 17、Maven 3.9.x、Python 3 和运行中的 Docker Desktop。脚本只使用 Python 标准库。环境构建使用本地 Docker CLI，原 POM 的远程 Docker 镜像构建未触发。

## 范围及限制

- 本地文件上传使用现有 `/minio/upload`，已验证上传及下载内容一致。MinIO 官方历史镜像/二进制渠道已不可用，因此按固定官方发布源码 commit 本地构建，镜像中保留 AGPL 许可证。
- 阿里云 OSS 和支付宝没有接入真实账户。OSS 仅配置明确的本地禁用占位以满足原代码的客户端构造要求；不要使用 OSS 接口进行真实上传。支付宝账户/签名凭据为空。
- 本环境面向本机后端开发。仓库不包含商城前端；未启动原样例中的 Nginx、Logstash、Kibana，应用使用独立控制台日志配置，因此不依赖该日志栈。
- RabbitMQ 3.9、MongoDB 4 和已归档的 MinIO 源码沿用本地兼容基线，只适合隔离开发验证。生产部署仍需评审受维护版本、真实凭据、TLS、备份和原审计指出的交易/安全问题。
- 所有端口只绑定回环地址；不要将此配置直接用于公网或生产。


## 交易迁移、可靠性测试与消息运维

```powershell
python environment/reliability.py migrate
python environment/reliability.py test
python environment/reliability.py status
```

迁移先检查订单号重复/缺失，冲突时停止，不修剪或猜测历史数据。测试新建带随机后缀的 `tradeflow_test_*` 数据库及 vhost，不在 `mall`、`/mall` 上清理测试数据；这些测试资源保留供检查。测试需要本机 MySQL、RabbitMQ 可用，否则明确失败。

admin 和 portal 共用的内部调用身份由 `prepare` 生成至被忽略的 `TRADEFLOW_INTERNAL_TOKEN`。内部关闭和重放接口在网关阻断，直连还必须验证该身份。不要把此令牌写入普通配置或日志。

修复失败原因后，确认重放最多 10 条失败消息：

```powershell
python environment/reliability.py replay
```

仅在目标队列确认接收后才确认原失败消息，重复投递由订单状态控制。支付 `REVIEW` 记录保留供人工核对，不自动退款或重新打开已关闭订单。客户端结算必须传入 `requestKey`，同一次请求重试复用该键。
