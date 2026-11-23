# mall-swarm 前端集成前的后端审计

初次审计时间：2026-11-17 13:32；授权恢复后复核：2026-11-17 16:30–16:32（Asia/Shanghai）。范围：当前工作区的源码、已存在的本机环境配置和只读运行检查。上一轮环境及交易可靠性报告仅作为历史线索，不能代替本次探测结果。

## 1. 当前结论与执行边界

**最新结论：用户授权恢复既有运行服务后，7 个应用和 7 个中间件均已健康，真实管理员登录、只读业务查询和文档均可访问，可以继续前端真实联调。** 详见第 10 节的实际复核结果。后端代码及现有配置未因本轮集成修改。

保留初始现场事实：13:32 时后端未运行，真实前后端联调被阻塞。7 个应用的健康接口均连接失败，网关、admin、portal 文档接口实际返回连接拒绝（WinError 10061）。没有发现这些应用或中间件所用端口的监听，也没有发现 Java 进程。

初次检查时 Docker Desktop 已安装，但未发现 Docker Desktop、com.docker.backend、dockerd 进程。Docker context 为 `desktop-linux`，`docker ps` 失败：`dockerDesktopLinuxEngine` 命名管道不存在。当时不能列出现有容器、检查它们的健康状态，或查询数据库。该事实不代表容器或数据卷丢失。

审计动作没有启动、重建或重新部署后端/中间件；没有运行初始化、迁移、建表或写 SQL，也没有修改业务代码、POM、原有配置及用户代码。后续主执行者在取得用户授权后，只启动已存在的 Docker 服务、容器和应用进程，未使用 `up`、pull 或 rebuild；本审计随后只读复核。

## 2. 已配置访问地址与实际状态

以下端口由当前 `environment/local.py` 的应用映射确认，不是源码样例中默认的 8080/8085/8201。表中保留初次离线结果，恢复后的实际健康结果见第 10 节。

| 服务 | 已配置本机地址 | 13:32 初次 `/actuator/health` 探测 |
| --- | --- | --- |
| Spring Cloud Gateway | `http://127.0.0.1:18201` | 连接失败，未取得 HTTP 响应 |
| mall-admin | `http://127.0.0.1:18080` | 连接失败，未取得 HTTP 响应 |
| mall-portal | `http://127.0.0.1:18085` | 连接失败，未取得 HTTP 响应 |
| mall-search | `http://127.0.0.1:18081` | 连接失败，未取得 HTTP 响应 |
| mall-auth | `http://127.0.0.1:18401` | 连接失败，未取得 HTTP 响应 |
| mall-demo | `http://127.0.0.1:18082` | 连接失败，未取得 HTTP 响应 |
| mall-monitor | `http://127.0.0.1:18101` | 连接失败，未取得 HTTP 响应 |

MySQL `13306`、Redis `16379`、RabbitMQ `5673/15673`、Nacos `18848/19848/18880`、MongoDB `27018`、Elasticsearch `19200`、MinIO `19000/19001` 在初次只读端口检查中也未发现监听。Engine 当时不可用，因此没有创建替代环境；恢复后原有服务仍使用这些端口。

## 3. 网关路由与前端基地址

源码 `mall-gateway/src/main/resources/application.yml` 配置了以下路由，均使用服务发现 `lb://服务名`，并通过 `StripPrefix=1` 去掉第一段路径：

| 前端网关路径 | 转发服务内路径 | 用途 |
| --- | --- | --- |
| `/mall-admin/**` | 去掉 `/mall-admin` | 管理员、商品、订单、营销与权限管理 |
| `/mall-portal/**` | 去掉 `/mall-portal` | 会员、商品查询、地址、购物车、订单 |
| `/mall-auth/**` | 去掉 `/mall-auth` | 统一认证入口 |
| `/mall-search/**` | 去掉 `/mall-search` | Elasticsearch 商品搜索 |
| `/mall-demo/**` | 去掉 `/mall-demo` | 示例接口，不用于商城正式业务流程 |

前端应统一经过网关，不把 admin/portal 的直连端口混入不同页面。配置 `VITE_BASE_SERVER_URL` 时必须结合克隆后的请求封装：如果 API 模块已经拼入 `/mall-admin`，全局基地址不能再重复这段前缀；如果封装仅含 `/product/list` 等服务内路径，则需在 PC 基地址或代理中补入管理服务前缀。

优先采用前端开发服务器的同源代理，将浏览器相对路径转发至 `127.0.0.1:18201`，在前端代理或请求模块内统一处理前缀。H5 还需区分 portal 商品查询和 search 服务路径。不能为了对接擅自修改后端路由或重新发布 Nacos 配置。

## 4. 登录认证与 Token 契约

以下为当前源码核对结果；初次服务离线时未声称登录成功。16:31 复核已取得真实管理员登录及受保护 API 调用成功证据，未记录任何口令或令牌值。

| 用途 | 方式及网关接口 | 参数与返回 |
| --- | --- | --- |
| 管理员统一登录 | `POST /mall-auth/auth/login` | 表单 `clientId=admin-app`、`username`、`password` |
| 会员统一登录 | `POST /mall-auth/auth/login` | 表单 `clientId=portal-app`、`username`、`password` |
| 管理员直接业务登录 | `POST /mall-admin/admin/login` | JSON `{username,password}`；与统一登录是两种可选入口 |
| 会员直接业务登录 | `POST /mall-portal/sso/login` | 表单 `username`、`password` |
| 管理员资料/权限菜单 | `GET /mall-admin/admin/info` | 需要管理员身份；返回 `username`、`menus`、`icon`、`roles` 等 |
| 会员资料 | `GET /mall-portal/sso/info` | 需要会员身份 |
| 管理员登出 | `POST /mall-admin/admin/logout` | 需要已登录身份 |
| 会员登出 | `POST /mall-portal/sso/logout` | 需要已登录身份 |

登录成功必须同时满足业务 `code=200`、存在 `data.token`；`data.tokenHead` 为 Bearer 前缀。请求头采用 `Authorization: Bearer <token>`，拼接时规范空格。不要把 Cookie 自动携带当成认证完成：当前 Sa-Token 设置不读取 Cookie，而读取请求头。

管理员和会员使用不同的登录类型/Session。PC 与 H5 应隔离自己的 Token 存储键，不能共用管理员 Token 测试购物车或订单。

网关认证异常可能保持 HTTP 200，但响应业务码为 401/403；其他错误也通过 `CommonResult` 表达。因此请求拦截器必须同时检查 HTTP 与业务码，过期时清除对应身份并导航至登录页。普通分页接口使用 `data.list`、`data.total`，不能直接把 `data` 当列表。

仅允许使用已有本机凭据或用户在页面输入的凭据。真实口令和令牌不得写入前端 `.env`、源码、控制台、公开报告或提交；现有本机凭据文件保持被 Git 忽略。

## 5. 前端需要调用的主要业务接口

以下接口均以网关 `http://127.0.0.1:18201` 为服务器地址。

| 场景 | 网关接口 | 认证/负载 |
| --- | --- | --- |
| 管理商品列表 | `GET /mall-admin/product/list` | 管理员；分页和筛选查询参数 |
| 商品新增 | `POST /mall-admin/product/create` | 管理员；官方商品聚合 JSON |
| 读取编辑表单 | `GET /mall-admin/product/updateInfo/{id}` | 管理员 |
| 商品修改 | `POST /mall-admin/product/update/{id}` | 管理员；商品聚合 JSON |
| 查询/更新 SKU | `GET /mall-admin/sku/{pid}`、`POST /mall-admin/sku/update/{pid}` | 管理员；更新为 SKU 列表 JSON |
| 管理订单列表/详情 | `GET /mall-admin/order/list`、`GET /mall-admin/order/{id}` | 管理员 |
| 后台关闭订单 | `POST /mall-admin/order/update/close` | 管理员；查询/表单参数 `ids`、`note`；受关闭权限及订单状态约束 |
| 管理后台用户列表 | `GET /mall-admin/admin/list` | 管理员；不是商城会员列表 |
| 首页 | `GET /mall-portal/home/content` | 公开；包含首页各内容区数据 |
| 商品分类树 | `GET /mall-portal/product/categoryTreeList` | 公开 |
| 商品查询 | `GET /mall-portal/product/search` | 公开；建议显式 `pageNum=1`、`pageSize` |
| 商品详情 | `GET /mall-portal/product/detail/{id}` | 公开 |
| 加入/读取购物车 | `POST /mall-portal/cart/add`、`GET /mall-portal/cart/list` | 会员；新增为购物车项 JSON |
| 调整数量 | `GET /mall-portal/cart/update/quantity` | 会员；参数 `id`、`quantity`，数量必须大于零 |
| 购物车促销/结算数据 | `GET /mall-portal/cart/list/promotion` | 会员；`cartIds` 查询参数 |
| 订单确认 | `POST /mall-portal/order/generateConfirmOrder` | 会员；JSON 数组 `cartIds`，不是对象包裹 |
| 创建订单 | `POST /mall-portal/order/generateOrder` | 会员；见下一节请求键契约 |
| 读取订单列表 | `GET /mall-portal/order/list` | 会员；必须传 `status`，`-1` 代表全部，另传分页参数 |
| 读取订单详情 | `GET /mall-portal/order/detail/{orderId}` | 会员；仅自己的订单 |
| 会员主动取消 | `POST /mall-portal/order/cancelUserOrder` | 会员；参数 `orderId`；不能用超时消息接口替代 |
| 收货地址列表/新增 | `GET /mall-portal/member/address/list`、`POST /mall-portal/member/address/add` | 会员；先选择有效的本人地址 |

源码中未发现独立的后台数据统计 controller。官方首页如果使用示例图表，必须标注演示数据，不能写成真实后端统计；如采用现有列表计算统计，应明确统计口径、分页覆盖范围及时间范围，不假设存在新的统计 API。

## 6. 本分支与官方前端的兼容性重点

1. **结算请求键新增。** `OrderParam.requestKey` 为必填，格式 `[A-Za-z0-9_-]{8,64}`；同一次结算重试必须复用，同键不同请求体会拒绝。需要由 H5 在开始结算时生成并持久保留，成功或明确开始新结算后更换；不能每次网络重试都生成新键。
2. **创建订单必需字段。** `memberReceiveAddressId`、有效非空且不重复的 `cartIds`、合法 `payType`、非负 `useIntegration`；`couponId` 可选。应使用真实购物车 ID 和本人地址。负数量、无效 SKU、下架商品和无权操作会失败，不通过改后端绕开。
3. **订单号是字符串。** 新订单号为 32 位 UUID 字符串，历史订单号保持原值。不得转为数字或按固定数字长度校验。
4. **付款不能模拟。** `/order/paySuccess` 已改为服务端支付宝查单；当前支付商户未完成配置的事实见上一轮报告，本次未联网复核。前端不能直接显示“支付成功”，也不能写死成功或篡改库存。创建未付订单、查看、取消可以作为联调终点。
5. **库存字段不可回写。** SKU 的 `lockStock` 由后端维护；前端不得用表单库存覆盖预留。当前后端会拒绝库存低于锁定量，拒绝删除仍有预留或未完成订单的 SKU，应展示真实业务错误。
6. **取消接口语义不同。** 会员主动取消用 `/cancelUserOrder`；`/cancelOrder` 是计划超时取消消息入口，`/cancelTimeOutOrder` 公开接口拒绝全局扫描。
7. **内部 API 不供前端使用。** `/internal/orders/**` 使用服务身份并在网关阻断；前端不能保存或传入内部服务令牌。
8. **管理权限有服务端约束。** 关闭订单需当前管理员拥有对应资源权限；不能只隐藏按钮后假设 API 一定成功。HTTP 200、变更数 0 或“已关闭”结果按当前业务契约处理。

## 7. CORS 与接口文档

静态 CORS 配置位于 `mall-gateway/src/main/java/com/macro/mall/config/GlobalCorsConfig.java`：允许全部方法、请求头及 origin pattern，允许 credentials；OPTIONS 在 Sa-Token 认证中放行。Sa-Token 错误处理另设置 `Access-Control-Allow-Origin: *`，最初列为待验证差异。恢复后的实际响应见第 10 节；不能仅凭静态配置宣称浏览器联调成功。

优先用前端同源代理解决 H5/PC 开发调用，不因这个潜在问题直接改后端。若恢复后经实际请求证实仍必须修改后端 CORS，应先追加问题报告并等待授权。

| 文档 | 配置地址 | 13:32 初次实际结果 |
| --- | --- | --- |
| 网关 Knife4j 聚合 | `http://127.0.0.1:18201/doc.html` | 连接拒绝 |
| admin Swagger UI | `http://127.0.0.1:18080/swagger-ui.html` | 连接拒绝 |
| portal Swagger UI | `http://127.0.0.1:18085/swagger-ui.html` | 连接拒绝 |
| admin OpenAPI（网关） | `http://127.0.0.1:18201/mall-admin/v3/api-docs` | 连接拒绝 |
| portal OpenAPI（网关） | `http://127.0.0.1:18201/mall-portal/v3/api-docs` | 连接拒绝 |

## 8. 测试数据与需要处理的事项

历史 `TRADEFLOW_RELIABILITY_REPORT.md` 记录上轮环境有 65 条订单、94 条订单项、38 个商品。这些在初次离线检查中仅作为 **2026-11-16 的历史记录**；恢复后另以只读 SQL 和 API 重新核实，见第 10 节。现有数据库不得清空、重建或重新初始化。

用户已授权主执行者恢复已存在的 Docker Desktop/Engine、既有 TradeFlow 容器和应用进程；已使用已有容器/卷和原有配置，没有重新安装/初始化。健康、路由、登录、CORS、文档和只读数据查询已补测，可继续通过业务 API 进行前端联调。

后续真实 E2E 只可通过已授权的商品新增/编辑、会员注册、购物车和订单 API 创建清楚标记的测试记录；不直接写 SQL。支付联调仍需真实商户/沙箱配置授权，不能自行补配或制造成功。

本报告仅把实际复核的管理员登录、只读查询和运行检查标记为通过。商品新增/修改、会员注册、购物车、下单及库存变化由后续 E2E 报告单独记录，不能从本报告的健康结果推定业务流程成功。

## 9. API 测试数据的字段契约（静态确认）

用于后续通过现有 API 创建清楚标记的测试记录，不是执行记录，也不允许绕过 API 写数据库。

- 会员先通过 `GET /mall-portal/sso/getAuthCode?telephone=...` 获取当前开发实现的验证码，再以表单字段 `username,password,telephone,authCode` 注册；源码只在 Redis 生成和缓存验证码，没有短信外发。用户名和电话号码都要唯一，不使用真实客户资料。
- 商品创建返回 `data=1` 变更数，不返回商品 ID。用唯一 `productSn`/名称查询管理商品列表后取 ID，再通过 `/mall-admin/sku/{pid}` 获取真实 SKU ID。商品应有 `name`、`productSn`、`deleteStatus=0`、`publishStatus=1`、`promotionType=0`、`giftPoint=0`、`giftGrowth=0`，价格/库存与 SKU 一致；分类和品牌应从现有 API 查询。SKU 至少提供正数 `price`、非负 `stock`，可以提供唯一 `skuCode`、`spData`。服务端创建时生成关系并把锁定库存设为 0。
- `productLadderList`、`productFullReductionList`、`memberPriceList`、`productAttributeValueList`、`subjectProductRelationList`、`prefrenceAreaProductRelationList` 没有测试需求时给空数组；不要覆盖真实商品的关系数据。
- 地址新增 JSON 使用 `name,phoneNumber,defaultStatus,postCode,province,city,region,detailAddress`，所属会员由服务端写入。新增只返回变更数，必须重新读取地址列表，取属于本测试会员的真实 ID。
- 加入购物车最小负载为 `{productId,productSkuId,quantity}`；真实购物车 ID 必须由购物车列表获取。创建订单后应看到 `lockStock` 增加、`stock` 保持不变；会员取消后 `lockStock` 恢复。不要通过付款接口制造扣减结果。
- 创建订单返回 `data.order` 和 `data.orderItemList`。同一结算重试的 `requestKey` 和业务请求体保持一致；成功后保留真实订单 ID/字符串订单号，供详情和取消验证。

## 10. 授权恢复后的实际复核结果

复核时间：2026-11-17 16:30–16:32。主执行者已获用户明确授权，使用现有服务启动方式恢复既有容器和现成应用 class。本节仅记录审计的只读检查和必要认证请求，没有执行商品/订单写入。

### 10.1 服务与数据状态

| 检查 | 实际结果 |
| --- | --- |
| 7 个应用 `/actuator/health` | monitor、admin、portal、search、auth、demo、gateway 均 HTTP 200 且 `status=UP` |
| 既有 `tradeflow-local` 容器 | MySQL、Redis、RabbitMQ、Nacos、MongoDB、Elasticsearch、MinIO 共 7 个，均 `healthy`；仍绑定本机回环端口 |
| 真实管理员统一登录 | `POST /mall-auth/auth/login` 业务码 200，存在 token，前缀为 Bearer；真实值只在内存中使用 |
| 管理员资料 | `GET /mall-admin/admin/info` HTTP 200、业务码 200，返回 24 个菜单 |
| 商品管理列表 | HTTP 200、业务码 200，`data.total=20`，测试每页 1 条能取得真实记录 |
| 订单管理列表 | HTTP 200、业务码 200，`data.total=48`，测试每页 1 条能取得真实记录 |
| 后台用户列表 | HTTP 200、业务码 200，`data.total=9` |
| 门户首页 | `/mall-portal/home/content` HTTP 200、业务码 200 |
| 门户分类树 | HTTP 200、业务码 200，6 个一级分组 |
| 门户商品查询 | HTTP 200、业务码 200，分页总数 20 |

同一时段只读 SQL 计数：商品总表 38，未删除且已上架商品 20，订单表 65，订单项 94，会员 11。管理 API 总数采用业务筛选条件，不等于包含逻辑删除数据的原始表总数，不能混作同一统计口径。这些计数属于后续 E2E 新增标记记录之前的审计时点，后续应按 E2E 实际时间说明增量。

### 10.2 文档和 CORS

| 检查 | 实际结果 |
| --- | --- |
| 网关 `http://127.0.0.1:18201/doc.html` | HTTP 200，HTML |
| admin `http://127.0.0.1:18080/swagger-ui.html` | HTTP 200，HTML |
| portal `http://127.0.0.1:18085/swagger-ui.html` | HTTP 200，HTML |
| 网关 `/mall-admin/v3/api-docs` | HTTP 200，OpenAPI 3.1.0，160 条 path |
| 网关 `/mall-portal/v3/api-docs` | HTTP 200，OpenAPI 3.1.0，67 条 path |

以 `Origin: http://127.0.0.1:5173`、管理商品列表为探针：

- OPTIONS 预检：HTTP 200，`Access-Control-Allow-Origin` 精确反射该 Origin，`Access-Control-Allow-Credentials=true`。
- 携带合法管理员 Authorization 的 GET：HTTP 200、业务码 200，同样反射 Origin 并允许 credentials。
- 不带身份的 GET：HTTP 200、业务码 401；`Access-Control-Allow-Origin=*`，没有 credentials 响应头。这是与成功响应不同的错误路径，前端必须检查业务码；本次未观察到“星号与 credentials=true 同时返回”的假定组合，不能将静态疑点写成已证实故障。

前端继续使用同源代理可统一处理开发调用；最终浏览器行为由 PC/H5 E2E 检查确认。本次不需要据此修改后端 CORS。

### 10.3 后端问题清单与授权边界

**敏感字段返回需要后续后端修复授权。** 实测管理员 `/mall-admin/admin/list` 返回的记录带非空 `password` 字段。审计只记录该字段是否存在，未输出或保存其哈希值。`/mall-portal/sso/info` 当前源码直接返回整个 `UmsMember`，同样存在模型密码字段返回风险；会员实际响应应在后续标记测试账户中核实，禁止打印完整资料响应。

前端应只白名单存储和展示所需资料，避免把完整用户对象写入 storage 或调试日志。该措施减少前端扩散，但不能消除后端响应本身的风险；真正移除或脱敏后端字段需用户授权，本轮未修改后端。

当前没有阻塞前端开发的服务离线问题。真实商户/支付沙箱未联调，不能伪造成功；商品写入、库存变化与订单流程仍需按 E2E 报告逐项取得实际结果。
