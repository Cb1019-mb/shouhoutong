# 售后通 SHOUHOUTONG

> 面向小型电商商家的 AI 售后接待工作台——AI 自动接待客户咨询,查询类操作直接执行,退款等高风险操作生成"操作预览"推人工审批,批准后真实写入订单库,全程审计可回放。

**差异化内核**:工具治理层(default-deny 策略引擎 + dry-run 预览 + 人工审批 + 审计回放)。
**北极星指标**:每周"经审批执行的敏感操作数";反指标:AI 答错引发的拦截数。

---

## 1. 架构

```
┌─ 浏览器 ─────────────────────────────────────────────────┐
│ 客户模拟器  店主工作台  审批中心  审计中心  数据看板  设置     │
└──────────────┬───────────────────────────┬───────────────┘
               │ SSE(流式+审批推送)         │ HTTP
        ┌──────▼───────────────────────────▼──────┐
        │   Spring Boot 单体(本仓库)                │
        │  chat        会话/消息/SSE                  │
        │  agent       ChatClient + system prompt     │
        │  governance  ★ @Governed 切面:策略引擎     │
        │             → dry-run → 审批 → 执行 → 审计  │
        │  approval    状态机/过期扫描/飞书适配(可关)  │
        │  rag         Infinity 嵌入 + Qdrant 检索    │
        │  dashboard   4 KPI + 命中分布 + 7 天趋势     │
        │  settings    app_setting 持久化运行时可调   │
        └──┬─────────┬─────────┬─────────┬──────────┘
   ┌───────▼──┐ ┌────▼────┐ ┌──▼─────┐ ┌─▼─────────────┐
   │ MySQL 8  │ │ Qdrant  │ │ vLLM   │ │ Infinity       │
   │ 业务+审计 │ │ 政策向量 │ │ 4B-AWQ │ │ bge-small-zh   │
   │ :3307    │ │ :6335/36│ │ :8000  │ │ :7997          │
   └──────────┘ └─────────┘ └────────┘ └────────────────┘
       MySQL/Infinity 跑 Docker;Qdrant 推荐原生进程
```

时序(高风险审批闭环):

```
客户 → tool_call:createRefund → 治理切面 → DryRunService 生成预览(零写库)
→ tool_audit(APPROVE) + approval_task(PENDING, 含 order_no 冗余)
→ 飞书卡片 / 网页审批中心 → 立即返回话术,请求结束(不阻塞线程)
→ 店主决定(网页/飞书,CAS 抢状态 PENDING→APPROVED/REJECTED/EXECUTE_FAILED)
→ 批准:ApprovalExecutionService 单事务执行(订单→REFUNDING + refund_request EXECUTED)
→ 结果消息落库 + SSE 实时推给客户 + 卡片更新终态
→ ExpireSweeper 每 60s 扫描,默认 24h 自动 EXPIRED(运行时可改)
```

---

## 2. 核心交互(从客户视角)

| 客户行为 | 系统响应 |
|---|---|
| 问"我的订单到哪了" | `queryLogistics` 直接查真实订单,工具治理 ALLOW |
| 问"我有哪些订单"(没给订单号) | `queryMyOrders` 列出本人名下订单,请买家确认,身份绑定只回本人 |
| 问"怎么退货" | `searchPolicy` 命中 RAG,带引用返回 |
| 申请退款 | `createRefund` 高风险 → dry-run → 店主飞书/网页审批 → 批准后订单真实变 REFUNDING |
| 重复申请退款 | 同订单 PENDING 阶段被拦截(防"幽灵审批") |
| 申请金额超实付 | 策略引擎 DENY(审计可见,不暴露规则键) |
| 店主未及时审批 | 24h 后自动 EXPIRED + 通知客户 |

---

## 3. 技术栈

| 组件 | 版本 | 部署 |
|---|---|---|
| JDK | 17 | Maven 构建 |
| Spring Boot | 3.4.5 | Servlet 栈(SseEmitter) |
| Spring AI | 1.0.0 | openai starter + qdrant starter |
| MyBatis-Plus | 3.5.12 | |
| MySQL | 8.0 | Docker,宿主端口 **3307** |
| Qdrant | 1.19.0 | 原生进程(推荐,原因见第 5 节),端口 6335(HTTP)/6336(gRPC) |
| vLLM | 0.10.2 | 原生进程,端口 8000 |
| Infinity | latest | Docker,端口 7997(`--url-prefix /v1`) |
| 飞书 | lark HTTP API | 默认关闭(`feishu.enabled=false`),走网页审批中心 |

---

## 4. vLLM 对话端点

- **端点**:Chat `http://localhost:8000/v1`,model=`qwen3-4b-awq`,SSE 流式
- **鉴权**:无(仅限本机,勿暴露到局域网)
- **启动参数必含**:`--tool-call-parser hermes --enable-auto-tool-choice --reasoning-parser qwen3 --max-model-len 8192 --gpu-memory-utilization 0.85`
- **思考处理双保险**:服务端 `--reasoning-parser qwen3` 分离时直接透传;未分离时应用侧 `ThinkFilter` 剥离 `<think>` 块

标准启动命令:

```bash
vllm serve <模型权重路径> \
  --served-model-name qwen3-4b-awq \
  --port 8000 \
  --tool-call-parser hermes --enable-auto-tool-choice \
  --reasoning-parser qwen3 \
  --max-model-len 8192 --gpu-memory-utilization 0.85
```

---

## 5. 快速启动(全新克隆视角)

环境要求:JDK 17 + Maven 3.9+ · Docker · 一个 OpenAI 兼容的对话端点(模型与推理服务自备,标准启动命令见第 4 节)。

```bash
git clone https://github.com/Cb1019-mb/shouhoutong.git
cd shouhoutong

# 0) (可选)配置:复制示例并按需修改;不创建也能用本地默认值跑通
cp .env.example .env

# 1) 基础设施(MySQL/Infinity 用 Docker;Qdrant 推荐原生运行——
#    Docker Desktop 端口代理不支持 gRPC 的 HTTP/2 先验连接,会打挂向量库客户端)
docker compose up -d mysql infinity
QDRANT__STORAGE__STORAGE_PATH=~/opt/qdrant-storage/storage \
QDRANT__SERVICE__HTTP_PORT=6335 QDRANT__SERVICE__GRPC_PORT=6336 \
  <qdrant 可执行文件路径>

# 2) 对话模型:OpenAI 兼容端点,默认 http://localhost:8000/v1(可用 LLM_BASE_URL 指向其他服务)

# 3) 构建并启动应用
mvn -Dmaven.test.skip=true package
java -jar target/shouhoutong-0.1.0-SNAPSHOT.jar

# 4) 浏览器
# http://localhost:8080/simulator  (客户模拟器)
# http://localhost:8080/approvals  (审批中心,飞书没接时在此批准/拒绝)
# http://localhost:8080/settings   (设置,改治理参数/外观)
```

首次启动会自动注入演示客户与订单,并把 `src/main/resources/policies/*.md` 的条款灌入向量库;表结构由 `docker/mysql-init/01-schema.sql` 初始化。

---

## 6. 页面

| 路径 | 功能 |
|---|---|
| `/simulator` | 客户模拟器:选客户发消息,流式看回复,实时收审批通知 |
| `/workbench` | 店主工作台:会话列表 + 聊天回放 |
| `/approvals` | 审批中心:预览 diff(改前→改后)+ 二次确认批准/拒绝 |
| `/audit` | 审计中心:全量工具调用台账,参数脱敏 |
| `/dashboard` | 数据看板:4 KPI(接待/拦截/退款金额/审批响应)+ 命中分布 + 7 天趋势 |
| `/settings` | 设置:模型温度/历史/语气,治理参数(阈值/限额/过期),暗色主题/字号 |

---

## 7. 设置板块(`/settings`)

| 组 | 项 | 消费方 | 生效 |
|---|---|---|---|
| 模型与对话 | 回复温度 / 历史条数 / 客服语气附加说明 | AgentService 实时读 | 下一条消息 |
| 业务规则 | 强制人工阈值 / 防重窗口 / 审批过期 / 券日限额 | 策略引擎/过期扫描实时读 | 保存即生效 |
| 外观 | 暗色主题 / 聊天字号 | 前端 CSS 变量 + localStorage | 立即,记住偏好 |

新增设置项只需在 `SettingKey` 枚举加一项(键/默认值/校验函数),API 与页面自动生效。

---

## 8. 测试

```bash
mvn test
```

覆盖:策略引擎、审批状态机(CAS/回写/通知)、治理切面、参数脱敏、`ThinkFilter`、退款资格、工具身份绑定。

---

## 9. 安全设计

- **身份绑定**:会话创建时服务端注册 `customerId`,LLM 不可见不可改。越权访问他人订单 → 拒绝并落双行审计(策略 ALLOW + 身份 DENY)。
- **策略引擎**:`@Governed` 切面 + `PolicyEngine` 读 YAML,default-deny,任一异常 fail-closed 拒绝。
- **dry-run 红线**:高风险工具提交时只生成 preview_json,零写库;批准后才开事务落库(订单状态机 + 退款单)。
- **CAS 抢状态**:审批 `WHERE status='PENDING'` 乐观更新,并发双点只生效一次。
- **执行失败回写**:EXECUTE_FAILED 状态 + 通知 + 转人工话术,避免"显示已批准却无退款单"的不一致。
- **参数脱敏**:手机号/密码/地址落审计前正则打码。
- **超时防护**:审批默认 24h 自动 EXPIRED(运行时可改),客户收到通知。

**已知边界(主动声明)**:①无真人鉴权,会话↔客户绑定是模拟器语义;②退款停在 REFUNDING,无支付回调闭环;③LLM 偶有语气瑕疵(4B 模型能力)。

---

## 10. 后续方向

- 5 个售后工具抽成独立 MCP Server,Claude Code / Cursor 等外部 Agent 经同一治理层调用
- 飞书 WebSocket 长连接(替代当前 HTTP 卡片)——按钮回调走 lark-oapi
- Spring AI 升级后支持 `chat_template_kwargs:{"enable_thinking":false}`,关闭项目侧 `ThinkFilter` 兜底

---

## 11. License

[MIT](LICENSE)
