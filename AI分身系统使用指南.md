# AI 分身系统使用指南

## 一、概述

AI 分身系统的核心目标是**模仿你本人的语言风格**在群聊中自动回复，而非追求答案正确性。它通过学习你的历史聊天记录（语气、措辞、口头禅、聊天习惯），生成看起来像你本人在说话的消息。

---

## 二、需要购买的模型 Token

系统需要两类模型的 API 额度：

### 1. LLM 大语言模型（必须）

用于生成回复文本，**每次回复都会消耗 Token**。

| 推荐模型 | 供应商 | 特点 | 参考价格 |
|---------|--------|------|--------|
| **qwen-plus**（默认） | [通义千问](https://bailian.console.aliyun.com/) | 中文优秀，性价比最优 | ¥0.8/百万 input Token |
| deepseek-chat | [DeepSeek](https://platform.deepseek.com/) | 性价比最高，中文能力强 | ¥1/百万 input Token |
| gpt-4o-mini | [OpenAI](https://platform.openai.com/) | 稳定，多语言 | $0.15/百万 input Token |
| moonshot-v1-8k | [Moonshot](https://platform.moonshot.cn/) | 国产，长上下文 | ¥12/百万 Token |

> **只要是 OpenAI 兼容接口（`/v1/chat/completions`）的模型都可以用。** 大部分国内厂商（DeepSeek、Moonshot、通义、智谱等）都支持。

**Token 消耗估算：** 每条回复约消耗 500~1500 Token（取决于上下文长度和回复长度），以 DeepSeek 为例，每天回复 50 条大约花费 ¥0.05~0.15。

### 2. Embedding 向量模型（必须）

用于将聊天记录转化为向量，支持 RAG 相似语义检索。

| 推荐模型 | 供应商 | 特点 | 参考价格 |
|---------|--------|------|--------|
| **text-embedding-v4**（默认） | [通义千问](https://bailian.console.aliyun.com/) | 最新一代，维度可选 | ¥0.5/百万 Token |
| bge-large-zh-v1.5 | [Silicon Flow](https://siliconflow.cn/) | 国产免费，中文优秀 | 免费 |
| text-embedding-3-small | [OpenAI](https://platform.openai.com/) | 效果好，维度 1536 | $0.02/百万 Token |

> Embedding 消耗量较小。索引 1000 条聊天记录大约消耗 5~10 万 Token，约 ¥0.01。

### 3. 省钱方案

- **LLM + Embedding 用同一家**：如都用通义千问，一个 API Key 搞定
- **最便宜组合**：qwen-plus LLM + text-embedding-v4（全栈通义，一个 Key）
- **最好效果组合**：qwen-plus LLM + text-embedding-v4（通义全家桶）

---

## 三、配置说明

### 环境变量配置（推荐）

在服务器上设置环境变量，避免密钥硬编码或提交到代码仓库：

```bash
# 必须配置（只需这一个 Key，LLM 和 Embedding 共用）
export AI_LLM_API_KEY="sk-xxxxxxxxxxxx"           # 通义千问百炼平台 API Key

# 可选配置
export AI_LLM_MODEL="qwen-plus"                    # LLM 模型名
export AI_PERSONA_NAME="你的名字"                    # AI 扮演的用户名
```

### application.yaml 完整配置项

```yaml
ai:
  enabled: true                          # ← 改为 true 启用
  llm:
    provider: qwen                       # 通义千问
    api-key: ${AI_LLM_API_KEY:}         # 只需配这一个 Key，Embedding 自动复用
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    model: qwen-plus                     # 性价比最优
    temperature: 0.85                    # 0~2，越高越有创造力
    max-tokens: 200                      # 单次回复最大 Token（建议 100~300）
  embedding:
    # api-key / base-url 留空，自动复用 llm 的配置
    model: text-embedding-v4             # 最新 Embedding 模型
    dimensions: 1024                     # v4 支持 1024/1536/2048，1024 性价比最优
  reply:
    max-per-day: 50                      # 每日回复上限（防刷屏/烧钱）
    cooldown-seconds: 30                 # 同群最短回复间隔
    random-rate: 0.15                    # 无触发条件时随机回复概率（15%）
    only-at: false                       # true = 仅被@时才回复
    context-size: 15                     # 拉取最近 N 条群消息作为上下文
    rag-top-k: 8                         # RAG 检索 N 条本人历史作为风格示例
  prompt:
    persona-name: ${AI_PERSONA_NAME:}   # 扮演的用户名（显示在 prompt 中）
    custom-instructions: ""              # 额外自定义提示词（追加到 system prompt）
```

### 各供应商 base-url 对照表

| 供应商 | base-url |
|--------|----------|
| **通义千问**（默认） | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| DeepSeek | `https://api.deepseek.com/v1` |
| OpenAI | `https://api.openai.com/v1` |
| Moonshot | `https://api.moonshot.cn/v1` |
| 智谱 AI | `https://open.bigmodel.cn/api/paas/v4` |
| Silicon Flow | `https://api.siliconflow.cn/v1` |

---

## 四、系统工作流程

```
群聊收到文本消息
    │
    ▼
┌─────────────────────────────────┐
│  1. 消息持久化（bot_chat_record）  │  ← 所有群消息都存，用于后续训练
└────────────┬────────────────────┘
             │
             ▼
┌─────────────────────────────────┐
│  2. 回复决策（ReplyDecisionService） │
│  判断是否该回复：                    │
│  · 被@提及 → 必须回复               │
│  · only-at 模式 → 不回复            │
│  · 超过每日上限 → 不回复             │
│  · 冷却期内 → 不回复                │
│  · 检测到提问 → 70% 概率回复         │
│  · 随机概率 → 按 random-rate 触发    │
└────────────┬────────────────────┘
             │ 决策：回复
             ▼
┌─────────────────────────────────┐
│  3. 并行拉取上下文                  │
│  · 最近 15 条群消息（上下文窗口）     │
│  · RAG 检索 Top-8 本人历史回复       │  ← 作为 few-shot 风格示例
│  · 发送者用户画像（如有）             │
└────────────┬────────────────────┘
             │
             ▼
┌─────────────────────────────────┐
│  4. 构建 Prompt                    │
│  · System: 角色指令 + 画像 + 自定义  │
│  · System: 历史风格示例（RAG）       │
│  · System: 最近上下文               │
│  · User: 发送者昵称 + 消息内容       │
└────────────┬────────────────────┘
             │
             ▼
┌─────────────────────────────────┐
│  5. 调用 LLM 生成回复               │
└────────────┬────────────────────┘
             │
             ▼
┌─────────────────────────────────┐
│  6. 回复审核（默认直接放行）          │  ← 预留接口，可插入敏感词过滤
└────────────┬────────────────────┘
             │
             ▼
┌─────────────────────────────────┐
│  7. 随机延迟 1~3 秒（模拟真人）      │
└────────────┬────────────────────┘
             │
             ▼
┌─────────────────────────────────┐
│  8. 发送到群聊                     │
└─────────────────────────────────┘
```

> 整个流程（步骤 2~8）在独立线程池异步执行，不阻塞 Webhook 回调。

---

## 五、如何喂聊天记录（RAG 训练）

### 自动采集（已内置）

系统已经自动完成聊天记录的采集和索引，无需手动操作：

1. **消息采集**：Webhook 收到目标群聊的文本消息时，自动存入 `bot_chat_record` 表（包括你自己发的消息，标记 `is_self=true`）
2. **定时索引**：每 10 分钟自动将未处理的聊天记录向量化并存入内存向量库（`@Scheduled` 定时任务）
3. **自动检索**：回复时自动从向量库中检索与你本人历史回复最相似的 Top-K 条，作为风格示例

### 前提条件

要让 RAG 生效，需要：

1. **先运行一段时间**：机器人需要在群里"潜水"收集足够多的聊天记录（建议至少 1-2 天）
2. **启用 AI 系统**：`ai.enabled: true` 且 LLM/Embedding API Key 已配置
3. **本人记录越多越好**：RAG 检索的是 `is_self=true` 的记录，所以你在群里说的话越多，AI 模仿得越像

### 数据量参考

| 本人消息数 | RAG 效果 |
|-----------|---------|
| < 50 条 | 效果较弱，风格不够明显 |
| 50~200 条 | 基本能模仿语气和常用词 |
| 200~1000 条 | 效果较好，能学到口头禅和聊天习惯 |
| > 1000 条 | 效果最佳 |

### 手动管理聊天记录

聊天记录存储在 MySQL 的 `bot_chat_record` 表中，关键字段：

```
bot_chat_record:
├── id               # 自增主键
├── msg_id + app_id  # 消息唯一标识（防重复）
├── room_id          # 群聊 ID
├── sender_wxid      # 发送者 wxid
├── sender_nick      # 发送者昵称
├── is_self          # 是否本人发送（RAG 核心过滤条件）
├── content          # 纯文本消息内容（向量化数据源）
├── processed        # 是否已向量化（false=待处理, true=已索引）
└── created_at       # 入库时间
```

如需手动查看索引进度：
```sql
-- 查看待索引记录数
SELECT COUNT(*) FROM bot_chat_record WHERE processed = false;

-- 查看已向量化记录数
SELECT COUNT(*) FROM bot_chat_record WHERE processed = true;

-- 查看本人消息数
SELECT COUNT(*) FROM bot_chat_record WHERE is_self = true;
```

---

## 六、注意事项

### 安全与风控

1. **API Key 不要硬编码**：使用环境变量 `${AI_LLM_API_KEY}` 传入，不要直接写在 yaml 里
2. **每日上限务必设置**：`max-per-day` 防止异常情况下无限回复导致 Token 费用爆炸
3. **Cooldown 间隔**：`cooldown-seconds` 防止短时间内连续回复（看起来像机器人刷屏）

### 防封号

4. **模拟延迟**：系统已内置 1~3 秒随机延迟，模拟人类反应时间
5. **回复频率**：默认每天最多 50 条，随机率 15%，不会每条都回
6. **避免敏感内容**：审核接口 `ReplyReviewService` 已预留，建议后续接入敏感词过滤

### 效果调优

7. **temperature 参数**：
   - `0.7~0.85`：偏稳重，回复较 predictable
   - `0.85~1.0`：偏活泼，更有创意但也可能说错话
   - 建议从 `0.85` 开始，根据实际效果调整
8. **max-tokens**：控制在 100~300 之间，群聊回复不需要太长
9. **persona-name**：填你在群里的昵称，prompt 会告诉 LLM 扮演这个人
10. **custom-instructions**：可以追加额外指令，比如 `"不要使用表情符号"` 或 `"经常使用哈哈哈"`

### 向量存储

11. **当前是内存存储**：使用 `InMemoryVectorStore`（ConcurrentHashMap），适合万级以下数据量
12. **重启丢失**：服务重启后向量库清空，定时任务会重新索引 `processed=false` 的记录
    - 如果需要持久化，后续可切换到 PGVector / Milvus / Qdrant（VectorStore 接口已抽象）
13. **Embedding 维度**：`dimensions` 配置必须和实际模型输出维度一致，否则会报错

### 已知限制

14. **仅支持文本消息**：图片、语音、视频等消息类型不会触发 AI 回复
15. **仅支持群聊**：私聊自动回复暂未接入
16. **审核接口为占位实现**：`PassThroughReviewService` 直接放行，生产环境建议替换

---

## 七、快速启用步骤

```bash
# 1. 设置环境变量（只需这一个 Key）
export AI_LLM_API_KEY="sk-your-dashscope-api-key"

# 2. 修改 application.yaml
#    ai.enabled: false  →  ai.enabled: true

# 3. 重启服务
# 4. 等待 30 秒（定时任务首次执行，开始索引历史记录）
# 5. 在群里发消息测试
```

如果不想用环境变量，也可以直接在 yaml 中直接填写 api-key（注意不要提交到 Git）。

---

## 八、日志关键字

排查问题时搜索以下日志关键字：

| 关键字 | 模块 | 说明 |
|--------|------|------|
| `[Pipeline]` | 流水线编排 | 整体流程日志（决策/LLM回复/发送结果） |
| `[Decision]` | 决策模块 | 是否回复及原因 |
| `[RAG]` | 向量检索 | 索引进度和检索结果 |
| `[ChatRecord]` | 消息采集 | 聊天记录入库情况 |
