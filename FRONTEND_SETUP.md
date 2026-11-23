# mall-swarm 前端运行说明

## 访问地址

| 项目 | 本机地址 |
| --- | --- |
| PC 管理后台 | http://127.0.0.1:15173/ |
| 移动商城 H5 | http://127.0.0.1:15174/ |
| 现有后端网关 | http://127.0.0.1:18201/ |
| 聚合接口文档 | http://127.0.0.1:18201/doc.html |

两个前端只监听回环地址，适合本机开发联调；没有部署到公网。H5已构建为可部署静态产物；生产服务器仍需配置 `/api` 到网关的代理，当前脚本不修改已有反向代理或中间件。

## 启动、检查、停止

在本仓库根目录 PowerShell 执行：

```powershell
.\start-frontend.ps1
.\check-frontend.ps1
.\stop-frontend.ps1
```

脚本调用 `frontend/manage.py`，只管理本工作区前端进程及其子进程。通过工作区标记核对 PID，不按端口任意杀进程；端口被其他进程占用时明确失败。重复启动复用健康前端，不启动第二份。停止不会停止 Java、Docker 或后端服务。

`check` 检查前端进程归属及页面服务是否就绪，不把 HTML HTTP200 当作业务成功；真实接口结果详见 `E2E_TEST_REPORT.md`。

本次启动后端获得了用户单独授权，只恢复现有 Docker Desktop、已有容器和已编译 Java 服务，没有重新安装或部署。前端脚本本身不包含这些操作。以后如果后端未运行，请先由后端环境维护者恢复，前端页面会显示真实请求失败，不伪造数据。

## 工具和依赖

本机使用 Node.js **24.15.0**、npm **11.12.1** 和既有 Python 3。没有替换全局 Node、Java 或 Maven。

| 前端 | 上游提交 | 实际安装的主要版本 |
| --- | --- | --- |
| [mall-admin-web](https://github.com/macrozheng/mall-admin-web) | `81fc17e5a19f452bd854b106a9d59fa1ed5c7eac` | Vue3.5.26、Vite7.3.0、Element Plus2.13.0、TypeScript5.9.3、vue-tsc3.2.1、Axios1.13.2 |
| [mall-app-web](https://github.com/macrozheng/mall-app-web) | `b1eb3cde1d74fbf62e72195bafd9322c8149f4c8` | Vue3.5.32、uni-app3.0.0-5000720260410001、Vite5.2.8、TypeScript5.5.4、vue-tsc2.0.28 |

PC 使用官方 lock 安装。移动仓库原 vue-tsc 与已解析 TypeScript 不兼容；只在该前端内调整了工具版本和 lock，完整类型检查及 H5 构建均实际执行。未修改后端 POM 或添加后端依赖。

首次复制项目到新机器时，先在两个前端各执行 `npm.cmd ci`。当前工作区依赖已经安装，正常启动不重复安装：

```powershell
Set-Location frontend/mall-admin-web
npm.cmd ci
npm.cmd run build
Set-Location ../mall-app-web
npm.cmd ci
npm.cmd run tsc
npm.cmd run test:contracts
npm.cmd run build:h5
```

PC 独立启动命令为 `npm.cmd run dev`，H5 为 `npm.cmd run dev:h5`；统一脚本更便于安全停止和查看状态。

## 接口配置

- PC：`VITE_BASE_SERVER_URL=/api/mall-admin`，MinIO上传，禁止使用示例云凭据。
- H5：`VITE_API_BASE_URL=/api/mall-portal`，本地 `VITE_USE_ALIPAY=false`。
- Vite `/api` 同源代理转发至 `http://127.0.0.1:18201`，去掉 `/api` 后保留服务前缀。
- PC 提供 `.env.tradeflow.example`。本地 `.env.development.local`/`.env.production.local` 已配置；启动脚本仅在文件不存在时复制模板，不覆盖现有本地配置。
- 开发、生产环境都不能继续使用官方演示API处理本机真实账号。生产构建中的相对API路径需要对应服务器代理。

接口契约详见 `BACKEND_AUDIT.md` 和 `FRONTEND_ARCHITECTURE.md`。

## 账号、日志和测试

现有本机管理员凭据仍在既有 `environment/.runtime/secrets.env`，本轮未修改。通过真实注册接口建立的专用联调会员凭据在 `frontend/.runtime/e2e-credentials.json`。用本机编辑器查看所需账号，不要复制到聊天、提交或截图明文。

运行日志和测试截图位于 `frontend/.runtime` 以及两个前端各自忽略的 `.runtime`。共享日志前请脱敏。前端已移除密码持久化；会员展示只保存必要字段。

可重跑的真实API联调命令：

```powershell
python frontend/e2e_api.py --self-test
python frontend/e2e_api.py
python frontend/manage.py self-test
```

第二条会通过业务API使用/新增清楚命名的测试会员、商品、地址、购物车和未支付订单，验证后取消测试订单并核对库存，不执行 SQL、清空数据库或伪造付款。它会产生真实测试记录，保留供检查。浏览器验证结果及尚未解决的问题见 `E2E_TEST_REPORT.md`、`BACKEND_ISSUES.md`。
