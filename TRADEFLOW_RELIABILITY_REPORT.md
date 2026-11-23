# TradeFlow 交易可靠性实施报告

依据：仓库根目录 `BASELINE_AUDIT.md` 的 R01–R14、R17–R18 和 MVP 路线。执行日期：2026-11-16。本文记录本轮实现，早先环境报告保留为历史记录。

## 已落实的保护

| 审计项 | 实现及边界 |
| --- | --- |
| R01、R02、R08、R18 支付入口 | 保留 `/order/paySuccess`，但只能为本人订单发起支付宝服务端查单；客户自报支付成功不能改变状态。支付页金额、标题、订单号取自服务端；回调验证 RSA 签名、app、seller、交易状态和流水绑定。查询异常返回明确业务错误，避免空指针。回调返回纯文本 `success`/`failure`。 |
| R04、R05 库存和状态 | SKU 数量按 ID 汇总并排序，SQL 条件递增预留；付款同时扣减真实库存和预留，关闭只解除预留。每次校验受影响行数，多 SKU 任意失败整笔回滚。订单行锁与 `WHERE status=0 AND delete_status=0` 条件更新保护状态，副作用、支付记录及操作历史在同一事务。 |
| R06、R07 可靠消息 | 下单与 outbox 同事务提交；独立发布任务领取带令牌的租约，等待 correlated confirm 并检查 returned message。失败退避重试，过期租约可重新领取，旧持有者不能确认新租约。原 Long 消息体、Direct/TTL/DLX 拓扑保留，头部携带事件和原截止时间，重试使用剩余 TTL。消费三次失败后经确认发布到持久失败队列。 |
| R09 后台关闭 | admin 先验证当前管理员与关闭资源权限，再经服务发现调用 portal 内部接口。portal 统一关闭订单、归还资源、写操作历史；内部接口验证共享服务身份，网关阻断 `/internal/**`。关闭重试返回成功及实际变更数。 |
| R10 SKU 编辑旁路 | 移除 `REPLACE INTO`。库存编辑和商品整体编辑共用受约束的 SKU 更新，校验所属商品，忽略提交的锁定库存，未提供的促销价保留。库存不能低于预留；预留未释放或存在待付款、待发货、已发货订单时不能删除 SKU。 |
| R11、R12 归属和输入 | 详情、支付、取消、删除、确认收货均校验会员归属。系统超时取消与会员取消分开。结算要求非空且不重复的购物车项、正数量、有效 SKU/商品/地址、非负积分和合法支付类型；购物车价格及商品资料从数据库重取。购物车改规格不能删除其他会员的记录。 |
| R13、R14 积分和重复下单 | `use_integration` 在订单插入前保存，`integration` 保持赠送积分含义。积分条件扣减、增量归还，优惠券按具体领取记录绑定订单后使用和归还。`(member_id, request_key)` 唯一及请求摘要保护重试；选中购物车行加锁，防止不同键重复结算同一购物车。结算采用 READ COMMITTED，避免等待请求锁后仍读取旧快照。 |
| R03 管理信息 | 六个业务/网关应用仅暴露 Actuator `health,info`，健康详情不公开，env/configprops 值始终隐藏；移除支付宝整体匿名放行，仅回调保留匿名。隔离 Nacos 配置同步采用这些限制。 |
| R17 测试入口 | admin 原有测试移入 `src/test/java`，保留内容。显式使用 `-DskipTests=false`；数据库和消息测试通过独立库/vhost 入口执行，缺少外部服务或必需套件被跳过时不会报告验证成功。 |

另补齐两个状态旁路：后台不能逻辑删除仍持有资源的未完成订单；批量发货存在非待发货项时整批回滚，不写虚假的发货历史。

公共请求日志只保留路径、方法、耗时等元信息，停止记录参数体及返回值，避免记录登录密码、令牌或支付页面内容。日志回归用例先在原切面上复现失败，再验证修复。

超时扫描每 30 秒补偿一次，仅扫描带 outbox 截止时间的新订单。提前到达的消息不会提前关闭订单；支付或关闭后重复消息无副作用，已删除订单的系统消息安全结束。历史样例订单不会因启用扫描任务而自动关闭。

消息仍可能重复投递。发布确认、租约、数据库条件更新和扫描补偿共同保护结果，不承诺端到端恰好一次。确认与退回语义依据 [Spring AMQP 3.2 文档](https://docs.spring.io/spring-amqp/reference/3.2/amqp/template.html)，失败隔离使用现有依赖提供的 [带发布确认的 recoverer](https://docs.spring.io/spring-amqp/reference/3.2/amqp/resilience-recovering-from-errors-and-broker-failures.html)。

## 数据与迁移

新增的表只有三张：

- `order_request_dedup`：会员请求键、摘要和关联订单。
- `payment_transaction`：渠道/商户订单号唯一，渠道/支付流水号唯一；保存已验证的金额、商户、状态和 `APPLIED`/`REVIEW` 结果。
- `outbox_event`：事件、截止时间、重试、发布确认及租约。时间使用 epoch 毫秒，避免 JDBC 与数据库会话时区混用。

新增订单号唯一索引、订单超时索引及订单项订单索引。SQL 位于 `document/sql/tradeflow/V1__transaction_reliability.sql`，入口为：

```powershell
python environment/reliability.py migrate
```

入口先检查空/缺失订单号及重复订单号，再创建表和索引；索引已存在时核对定义。冲突时停止，不删除或修改冲突数据。当前隔离 `mall` 已应用这套增量结构；原始 dump、原有数据卷及样例数据保留，没有重新导入原 SQL。生产应用前应备份并评估加索引的锁等待和执行时间。

新订单号为 32 位 UUID 字符串，数据库唯一索引兜底，不依赖 Redis 计数。历史订单号继续按原值查单。

## 接口接入变化

`POST /order/generateOrder`（网关前缀为 `/mall-portal`）必须新增结算请求键：

```json
{
  "requestKey": "new-checkout-uuid",
  "memberReceiveAddressId": 1,
  "cartIds": [1],
  "payType": 1,
  "useIntegration": 0
}
```

示例 ID 仅说明字段，不能当成真实数据。键为 8–64 位字母、数字、下划线或连字符；同一次结算重试复用，新结算更换。同键不同请求内容拒绝，同键相同内容返回已创建的订单。客户端按字符串处理订单号，不假设旧的纯数字长度。仓库内没有商城前端，仓库外调用方未修改；旧客户端不传键时会收到明确校验失败。

`/alipay/pay`、`/alipay/webPay`、`/alipay/query` 需要会员身份；只有 `/alipay/notify` 匿名接收渠道通知。`/order/paySuccess` 的 `payType=1` 触发实际查单，其他渠道没有伪造的成功实现。公开的 `/order/cancelTimeOutOrder` 拒绝会员触发全局扫描，改由内置系统任务执行。

admin 与 portal 使用同一个至少 32 字符的 `tradeflow.internal-token`；本机脚本生成并通过被忽略的 `TRADEFLOW_INTERNAL_TOKEN` 注入。普通 Nacos 文件仅含占位符，不保存真实身份令牌。内部接口不加入公开白名单。

## 支付异常与消息运维

符合签名和商户身份的资金事实，若金额不符、订单已关闭或预留异常，则保存 `REVIEW` 及原因，不将订单标为已付，不重新打开订单，也不自动退款。待付款订单存在支付事实时不继续释放资源，交给人工核对。回调在事实和决定成功提交后确认接收；这不表示 `REVIEW` 订单支付已入账。查单响应由现有支付宝 Java SDK 验签，查询 API 没有 seller_id 字段，使用已配置商户应用的签名响应；异步通知单独核对 seller_id。校验依据见[支付宝通知校验说明](https://open.alitrip.com/docs/doc.htm?articleId=106448&docType=1&treeId=204)。

```powershell
python environment/reliability.py status
```

此命令只输出支付状态/原因的汇总、outbox 待发布/已确认数量和失败队列数量，不输出客户资料或密钥。

失败消息位于 `/mall` 下的 `mall.order.cancel.failed`。先修复失败原因，再执行：

```powershell
python environment/reliability.py replay
```

每次最多重放 10 条，经原目标队列确认接收后才确认失败队列中的原消息；未确认则保留原消息。消息重放不会重置订单截止时间。历史订单中缺失的积分使用数、未绑定订单的优惠券和异常预留不自动猜测修复。

## 验证记录

Java 17.0.21、Maven 3.9.16；没有升级框架、驱动或数据库版本。10 份 POM、原始数据库 dump 和两份原 Compose 共 13 个文件与环境阶段的哈希基线一致。

| 验证 | 实际结果 |
| --- | --- |
| 全仓清理后编译、测试、生成运行 classpath | `clean test dependency:build-classpath`，10 个 reactor 项目 `BUILD SUCCESS`，未执行 package。 |
| 原有用例 | 11 项执行通过，0 失败、0 跳过；包含恢复源目录后的 admin 两项。 |
| 新增可靠性与日志用例 | 49 项执行通过，0 失败、0 错误、0 跳过；19 项单元/协议检查，30 项真实 MySQL/RabbitMQ 集成检查。 |
| 同键并发/库存 | 6 个同键结算返回同一订单；8 个请求争抢最后 1 件库存只有一个成功；重复 SKU 汇总，多 SKU 失败整笔回滚。 |
| 支付/取消竞争 | 每次运行执行 8 轮竞争；重复回调仅扣库存一次，关闭后到账保留 REVIEW；签名、商户、金额、归属及 SDK 错误路径有回归。 |
| 事务与租约故障 | 提交前异常同时回滚订单、商品、预留、请求记录和 outbox；6 个竞争发布者仅一个拿到租约，过期租约恢复，旧令牌不能确认。 |
| RabbitMQ | 实际确认发布、不可路由退回、原 TTL/DLX 转发、三次重试隔离、失败重放和非法消息体处理均通过。负确认与连接异常另有单元故障注入。 |
| 信息泄露回归 | 回调空 HTTP 响应体、公共日志记录密码均先运行测试复现失败，修复后通过。 |
| 新内容凭据检查 | 没有将当前私密环境中的实际密码/令牌写入新增内容；私密运行文件由 Git 忽略。 |

全仓普通测试执行时，30 项外部集成检查按设计跳过，不能将那次执行算作它们通过；它们随后在独立入口中实际执行并通过。原有 11 项与新增 49 项合计 **60 个不同用例**，分组验证，未重复累计同一用例。

可重现入口：

```powershell
# 编译所有模块，生成直接启动所需 classpath，显式跳过执行测试
python environment/local.py build
# 原有测试与普通单元/协议测试；使用本地中间件和隔离 namespace
python environment/local.py test
# 必需集成套件不得跳过；新建独立数据库和 vhost
python environment/reliability.py test
python environment/reliability.py self-test
python environment/local.py self-test
```

根 POM 的 `skipTests=true` 保留，上述两个测试入口均显式覆盖为 `false`。只想按首轮约定编译 portal 及依赖时仍可使用 `mvn -pl mall-portal -am -DskipTests=true test`，该命令不代表执行过测试。

证据位于被忽略的 `environment/.runtime/`：`full-verification.log`、`original-test-results.json`、`reliability-tests.log`、`reliability-tests.json`、`reliability-results.json`、`reliability-secret-check.json`。测试 schema/vhost 名称保存在运行 JSON 中，保留供检查，未清理开发库数据。

最终运行检查：7 个应用已使用新代码重启并全部 `UP`，7 个中间件容器健康，Nacos 的隔离 namespace/group 内 7 个服务均有健康实例。实际验证了网关管理员登录、后台资源权限、Feign 经服务发现到 portal 的受保护调用；用负数订单 ID 触发输入校验，没有关闭真实订单。

所有应用的敏感 Actuator `env/configprops` 端点均不可访问；匿名支付查询被拒绝，内部接口缺失/错误身份返回 403，正确服务身份进入业务校验。未配置商户时回调 HTTP 200、`text/plain`、正文 `failure`。实际登录及内部调用后的新增日志片段中未出现登录密码、访问令牌或内部令牌。

开发库保留 65 条订单、94 条订单项、38 个商品；运行检查前后均没有新增支付事实和 outbox 事件，失败队列为 0。运行证据另见 `reliability-runtime-verification.json`。支付、并发、回滚和消息故障数据仅写入独立测试库/vhost。

## 发布前仍需处理

- 接入仓库外前端的请求键和订单号字符串处理，验证真实调用方；后端不为旧客户端随机补键。
- 配置真实支付宝 app、seller、应用私钥、公钥及 HTTPS 回调地址，完成沙箱联调和对账验收。目前本地未配置真实商户，支付接口保持失败关闭。
- 核对历史异常订单、积分及优惠券绑定；`REVIEW` 需业务人员决定退款或履约，不自动猜测处理。
- 生产迁移需要备份、维护窗口及内部身份注入；后端和中间件限制在内网，仅公开必要网关入口。本轮只应用本机隔离环境，未部署生产。
