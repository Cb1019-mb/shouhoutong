# 售后通 SHOUHOUTONG

> 面向小型电商商家的 AI 售后接待工作台——AI 自动接待客户咨询,查询类操作直接执行,退款等高风险操作生成"操作预览"推人工审批,批准后真实写入订单库,全程审计可回放。

**差异化内核**:工具治理层(default-deny 策略引擎 + dry-run 预览 + 人工审批 + 审计回放)。
**北极星指标**:每周"经审批执行的敏感操作数";反指标:AI 答错引发的拦截数。

---

## 1. 现状(截至 2026-08-31)

| 模块 | 状态 |
|---|---|
| **W1** 骨架:SSE 流式对话接 vLLM `qwen3-4b-awq` | ✅ |
| **W2** 治理:6 工具 + 策略引擎 + 身份绑定 + 审计 + RAG 政策问答 | ✅ |
| **W3** 审批:dry-run → 飞书/网页审批 → 真实落库 → SSE 通知 → 24h 过期 | ✅ |
| **W4** 看板 + 19 个单测 + 50 挂起+10/10 并发压测 + 文档 | ✅ |
| **审查修复** 提交防重 / 执行失败回写 / 历史恢复 / 审批确认 / 看板过滤 / 话术收紧 | ✅ |
| **设置板块** app_setting 持久化 + 治理参数运行时可调 + 暗色主题 | ✅ |
| **交互优化** 审批护栏 + 全站中文化 + 风险色 + 响应式/无障碍 + 加载体验 + 评审修复 5 项 | ✅ |
| **代码重构** 5 批次:ToolGuards/ToolJson 单一事实源、死代码清除、状态枚举、审批落库事务化、全局异常;单测 26→34 | ✅ |

---

## 2. 架构

```
┌─ 浏览器 ─────────────────────────────────────────────────┐
│ 客户模拟器  店主工作台  审批中心  审计中心  数据看板  设置     │
└──────────────┬───────────────────────────┬───────────────┘
               │ SSE(流式+审批推送)         │ HTTP
        ┌──────▼───────────────────────────▼──────┐
        │   Spring Boot 单体(本仓库,WSL2 内)        │
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
       MySQL/Infinity 在 Docker;Qdrant/vLLM 原生跑 WSL2
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

## 3. 核心交互(从客户视角)

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

## 4. 技术栈(版本锁定)

| 组件 | 版本 | 部署 |
|---|---|---|
| JDK | 17.0.17 | WSL conda base |
| Spring Boot | 3.4.5 | Servlet 栈(SseEmitter) |
| Spring AI | 1.0.0 | openai starter + qdrant starter |
| MyBatis-Plus | 3.5.12 | |
| MySQL | 8.0 | Docker,宿主端口 **3307** |
| Qdrant | 1.19.0 | **WSL2 原生跑**(`~/opt/qdrant`),端口 6335(HTTP)/6336(gRPC) |
| vLLM | 0.10.2(`pros-llm` env) | WSL2 原生,端口 8000 |
| Infinity | latest | Docker,端口 7997(`--url-prefix /v1`) |
| 飞书 | lark HTTP API | `feishu.enabled=false` 走网页审批中心(降级路径) |

---

## 5. vLLM 推理标准(已对齐)

- **端点**:Chat `http://localhost:8000/v1`,model=`qwen3-4b-awq`,SSE 流式
- **鉴权**:无(仅限本机,勿暴露到局域网)
- **启动参数必含**:`--tool-call-parser hermes --enable-auto-tool-choice --reasoning-parser qwen3 --max-model-len 8192 --gpu-memory-utilization 0.85`
- **思考处理双保险**:服务端 `--reasoning-parser qwen3` 分离时直接透传;未分离时应用侧 `ThinkFilter` 剥离 `<think>` 块(7 个单测覆盖跨 token 拆分等场景)
- **使用规范**:重启电脑后不自启,需手动启动。标准启动命令:

```bash
vllm serve <模型权重路径> \
  --served-model-name qwen3-4b-awq \
  --port 8000 \
  --tool-call-parser hermes --enable-auto-tool-choice \
  --reasoning-parser qwen3 \
  --max-model-len 8192 --gpu-memory-utilization 0.85
```

---

## 6. 快速启动(全新克隆视角)

环境要求:JDK 17 + Maven 3.9+ · Docker · 一个 OpenAI 兼容的对话端点(作者用 vLLM 跑 Qwen3-4B-AWQ,模型与推理服务请自备)。

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
#    vLLM 标准启动命令见第 5 节

# 3) 构建并启动应用
mvn -Dmaven.test.skip=true package
java -jar target/shouhoutong-0.1.0-SNAPSHOT.jar

# 4) 浏览器
# http://localhost:8080/simulator  (客户模拟器)
# http://localhost:8080/approvals  (审批中心,飞书没接时在此批准/拒绝)
# http://localhost:8080/settings   (设置,改治理参数/外观)
```

应用首次启动会自动:创建 8 张表 + 注入 3 客户 + 注入 16 笔订单 + 灌入 15 个政策条款向量。

---

## 7. 页面

| 路径 | 功能 |
|---|---|
| `/simulator` | 客户模拟器:选客户发消息,流式看回复,实时收审批通知 |
| `/workbench` | 店主工作台:会话列表 + 聊天回放 |
| `/approvals` | 审批中心:预览 diff(改前→改后)+ 二次确认批准/拒绝 |
| `/audit` | 审计中心:全量工具调用台账,参数脱敏 |
| `/dashboard` | 数据看板:4 KPI(接待/拦截/退款金额/审批响应)+ 命中分布 + 7 天趋势 |
| `/settings` | 设置:模型温度/历史/语气,治理参数(阈值/限额/过期),暗色主题/字号 |

---

## 8. 设置板块(`/settings`)

| 组 | 项 | 消费方 | 生效 |
|---|---|---|---|
| 模型与对话 | 回复温度 / 历史条数 / 客服语气附加说明 | AgentService 实时读 | 下一条消息 |
| 业务规则 | 强制人工阈值 / 防重窗口 / 审批过期 / 券日限额 | 策略引擎/过期扫描实时读 | 保存即生效 |
| 外观 | 暗色主题 / 聊天字号 | 前端 CSS 变量 + localStorage | 立即,记住偏好 |

新增设置项只需在 `SettingKey` 枚举加一项(键/默认值/校验函数),API 与页面自动生效。

---

## 9. 测试与压测

```bash
mvn test       # 37 个单测全绿:策略引擎 8 + 四态机 8 + 脱敏 3 + ThinkFilter 7
               # + 治理切面 3(DENY/APPROVAL/ALLOW 三分支)+ 审批状态机 5(CAS/回写/通知)
               # + 工具 3(queryMyOrders 身份绑定/空列表/缺身份拒绝)
```

**并发压测**(已验证):50 个 PENDING 审批挂起 + 10 路并发 SSE 对话 → 10/10 全部完成,21s 跑完。审批异步挂起不占请求线程。

---

## 10. 安全设计(面试可讲)

- **身份绑定**:会话创建时服务端注册 `customerId`,LLM 不可见不可改。越权访问他人订单 → 拒绝并落双行审计(策略 ALLOW + 身份 DENY)。
- **策略引擎**:`@Governed` 切面 + `PolicyEngine` 读 YAML,default-deny,任一异常 fail-closed 拒绝。
- **dry-run 红线**:高风险工具提交时只生成 preview_json,零写库;批准后才开事务落库(订单状态机 + 退款单)。
- **CAS 抢状态**:审批 `WHERE status='PENDING'` 乐观更新,并发双点只生效一次。
- **执行失败回写**:EXECUTE_FAILED 状态 + 通知 + 转人工话术,避免"显示已批准却无退款单"的不一致。
- **参数脱敏**:手机号/密码/地址落审计前正则打码。
- **超时防护**:审批默认 24h 自动 EXPIRED(运行时可改),客户收到通知。

**已知边界(主动声明)**:①无真人鉴权,会话↔客户绑定是模拟器语义;②退款停在 REFUNDING,无支付回调闭环;③LLM 偶有语气瑕疵(4B 模型能力)。

---

## 11. 交互优化(2026-08-31,设计评审驱动,零 Java 改动)

### 11.1 审批中心(资金决策护栏)

- 原生 confirm 替换为**设计内确认层**:风险色大号金额 + 订单/商品/客户/状态迁移 + 后果明示;Esc/遮罩可关,焦点陷阱(Tab 循环)+ 关闭后焦点还原
- 批准(实心)/拒绝(描边弱化)建立主次层级;提交有"执行中…"状态与网络异常提示
- 审批卡新增"查看对话 →"直达来源会话(approval_task.conversationId),消除审批盲批
- EXECUTE_FAILED 徽章配套恢复指引("资金未变动,转人工处理")
- 新任务轮询改为**比较任务 ID 集合**(原数量对比在增减相抵时漏检);弹层打开/页面不可见时不刷新打断

### 11.2 全站中文化与审计结构化

- 新增 `static/js/i18n.js`:订单状态/任务状态/裁决/风险中文映射(`data-zh` 标记批量翻译),金额统一两位小数
- 审计中心:参数/结果列从原始 JSON 改**紧凑键值 + "详情"折叠块**,`reply_guidance` 内部指令不再外显;后端 240 字符截断的残缺 JSON 走 60 字符预览兜底
- ISO 时间戳净化(历史下拉 `08-31T18:15` → `08-31 18:15`)

### 11.3 风险视觉权重与暗色主题

- 新增 `--risk/--risk-bg` 风险色 token(亮 #a03030 / 暗 #d98484),仅用于退款金额、DENY 徽章、EXECUTE_FAILED、错误文案——"拦截 vs 放行"首次有视觉表达
- 看板图表颜色实时读 CSS 变量(暗色不再黑柱隐身),select 箭头/错误色/徽章全部去硬编码

### 11.4 响应式与无障碍

- 5 页补 viewport meta;960px 断点(导航换行/KPI 2×2/图表单列)、640px(侧栏横向化/双列改单列)
- settings 全部 label 补 `for`;全站键盘焦点 outline(`:focus-visible`);状态行/toast `aria-live`;全站内联 SVG favicon

### 11.5 失败兜底(模拟器)

- 创建会话/发送消息 fetch 全部 try/catch,失败解锁按钮 + 可操作提示,发送失败**回填原文**重试无需重新打字
- 历史会话下拉首次加载即填充;流式中也可刷新列表(仅禁止恢复旧会话)

### 11.6 数据看板加载体验

- ECharts CDN 从 head 移至页底(原同步加载阻塞整页解析约 4s 白屏);骨架 + 中央"数据加载中"旋转层先行渲染,数据到达后退场,失败显示重试提示
- ECharts 实例复用(修 30s 轮询反复 init 的内存泄漏)+ resize 监听 + CDN 失败降级文案

### 11.7 设置页健壮性

- `load()` 加错误处理(原失败静默且保存按钮永久锁死);保存请求加网络/HTTP 兜底
- 脏状态升级为**与原值比对**(改回原值自动重新禁用),保存成功后同步原值

### 11.8 已知取舍(未做,需后端支持)

- 批准后 5s 可撤销窗口(需新增 CANCELLED 状态 + API + 看板/扫描器联动)
- 审计按风险/裁决筛选(需查询参数支持);ECharts 仍走 CDN(内网可下载至 static/js 本地托管)

---

## 12. 代码重构(2026-08-31,5 批次,行为保持)

按"低风险高收益优先"分 5 批执行,每批独立提交、独立验证:

| 批次 | 内容 | 关键收益 |
|---|---|---|
| ① | `common/GlobalExceptionHandler` + 飞书解析改 Jackson + prompt 预读 | 业务错 400+error 体;消除手写 JSON 解析脆弱性 |
| ② | `OrderStatus` 枚举化(表结构不变) | 消灭 6+ 文件状态字面量拼写隐患 |
| ③ | `ToolGuards`(越权校验 7 处收敛)+ `ToolJson`(3 处收敛)+ **清除 `createRefund` 不可达死代码** | 消除"双份四态逻辑"的最大维护陷阱 |
| ④ | 删除被设置功能绕过的 yml 死配置字段 | 配置单一真相 |
| ⑤ | 审批审计+任务 `@Transactional` 同写 + SSE 心跳 + 补 8 个单测 | 审计完整性事务保证;26→34 全绿 |

每批均以"单测全绿 + 对应 E2E 链路一致"验收;批次③ 重跑了越权/退款/防重/政策四条链路,批次⑤ 重跑了完整审批闭环(提交→批准→REFUNDING→通知)。

---

## 13. 二期方向(简历可写"后续规划")

- 5 工具抽成独立 MCP Server,Claude Code / Cursor 等外部 Agent 经同一治理层调用
- 飞书 WebSocket 长连接(替代当前 HTTP 卡片)——按钮回调走 lark-oapi
- Spring AI 升级后支持 `chat_template_kwargs:{"enable_thinking":false}`,关闭项目侧 `ThinkFilter` 兜底

---

## 14. License

[MIT](LICENSE)
