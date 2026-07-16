# AI 数字分身系统 — 完整架构说明

> **版本**: Prompt v3.4 | **技术栈**: Java 17 / Spring Boot 4.1.0 / MySQL / 通义千问  
> **最后更新**: 2026-07-12

---

## 一、系统概述

本系统是一个**长期学习的 AI 数字分身**，在微信群聊/私聊中代替用户本人回复消息。它不是普通聊天机器人，而是一个持续学习用户语言风格、表达习惯、兴趣、经历、人际关系的「数字克隆」。

**核心设计原则**：
- 三层 Prompt 架构（稳定规则 + 动态上下文 + 当前消息）
- 记忆是辅助参考，不是聊天素材（Anti-Overuse）
- 风格抑制层（Style Suppression）— 风格是统计特征，不是固定规则
- 话题锚定防御（MMR 重排 + 状态感知 + 反锚定提示）
- 人格漂移检测（长度/格式/AI特征/系统泄露 四维校验）
- 记忆生命周期管理（importance 评分 + TTL 过期 + 双轨读取）
- Style RAG 多样性平衡（规则打标签 + 特色表达占比限制）
- 动态 Temperature（场景感知：normal/humor/question 不同温度）

---

## 二、架构总览

```
微信消息 (Webhook)
    │
    ▼
┌─────────────────────────────────────────────┐
│              ReplyDecisionService           │  ← 决策层：是否应该回复？
│  @提及 > only-at > 提问检测 > 冷却 > 随机    │
└──────────────────┬──────────────────────────┘
                   │ shouldReply=true
                   ▼
┌─────────────────────────────────────────────┐
│              AiReplyPipeline                │  ← 13步异步流水线
│                                             │
│  1. 决策                                    │
│  2. 话题提取 + 状态更新                      │
│  3. RAG 必要性判断                           │
│  4. 上下文拉取 + 对话摘要                    │
│  5. Style RAG（向量检索本人历史）             │
│  6. Memory RAG（结构化记忆检索）              │
│  7. 话题过热检查 + 反锚定提示                │
│  8. Prompt 构建（三层架构）                  │
│  9. LLM 调用 + JSON 解析                    │
│  10. 审核（空值检查 + PersonaValidator）      │
│  11. 模拟人类延迟（1-3s 随机）               │
│  12. 发送微信消息                            │
│  13. 记忆提取 + 重要性评分 + 存储             │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────┐
│           WechatApiService.sendText()        │  ← 通过微信 API 发送
└─────────────────────────────────────────────┘
```

---

## 三、模块详解

### 3.1 决策层 — `ai/decision/`

| 文件 | 类 | 职责 |
|------|---|------|
| `ReplyDecision.java` | ReplyDecision | 决策结果 DTO（shouldReply + reason） |
| `ReplyDecisionService.java` | ReplyDecisionService | 多条件决策引擎 |

**决策优先级链**：
1. **@提及** → 必须回复（仅群聊）
2. **only-at 模式** → 仅@时回复（仅群聊）
3. **每日上限** → 达到 maxPerDay 则跳过
4. **冷却检查** → cooldownSeconds 内不重复回复同一群
5. **提问检测** → 正则匹配疑问词/问号，命中则按 questionRate 概率回复
6. **随机概率** → randomRate 触发（群聊 0.2，私聊 1.0）

**关键参数**（`application.yaml`）：
```yaml
reply:
  max-per-day: 10000        # 每日上限
  cooldown-seconds: 1       # 同群最短间隔
  random-rate: 0.2          # 群聊随机概率
  only-at: false            # 仅@模式
  private-chat:
    random-rate: 1          # 私聊几乎必回
    buffer-seconds: 5       # 消息聚合等待
```

---

### 3.2 LLM 层 — `ai/llm/`

| 文件 | 类 | 职责 |
|------|---|------|
| `LlmClient.java` | LlmClient (接口) | LLM 调用抽象 |
| `OpenAiCompatibleClient.java` | OpenAiCompatibleClient | OpenAI 兼容格式实现 |
| `LlmMessage.java` | LlmMessage | 消息 DTO（system/user/assistant） |
| `LlmReply.java` | LlmReply | 结构化回复 DTO + JSON 解析器 |

**LlmReply 结构化输出**（json-reply-format=true 时）：
```json
{
  "reply": "还行哈哈",
  "confidence": 0.87,
  "memory_used": ["friend_relationship"],
  "reply_reason": "对方在闲聊，轻松回应",
  "should_update_memory": false
}
```

**当前供应商**：通义千问 qwen-plus（¥0.8/M input tokens）

---

### 3.3 Embedding 层 — `ai/embedding/`

| 文件 | 类 | 职责 |
|------|---|------|
| `EmbeddingService.java` | EmbeddingService (接口) | 文本→向量转换 |
| `OpenAiEmbeddingService.java` | OpenAiEmbeddingService | OpenAI 兼容实现 |

**当前模型**：text-embedding-v4（1024 维，通义千问）
**批量限制**：单次最多 10 条，超过自动分批
**API 复用**：Embedding 自动复用 LLM 的 apiKey/baseUrl（无需额外配置）

---

### 3.4 向量存储 — `ai/vectorstore/`

| 文件 | 类 | 职责 |
|------|---|------|
| `VectorStore.java` | VectorStore (接口) | 向量存储抽象 |
| `InMemoryVectorStore.java` | InMemoryVectorStore | 内存实现（ConcurrentHashMap） |
| `VectorDocument.java` | VectorDocument | 文档 DTO（id + vector + metadata） |
| `VectorSearchResult.java` | VectorSearchResult | 搜索结果 DTO（id + score + metadata） |

**相似度算法**：余弦相似度（cosine similarity）
**扩展路径**：接口已抽象，后续可切换到 PGVector / Milvus / Qdrant

---

### 3.5 检索层 — `ai/retrieval/`

| 文件 | 类 | 职责 |
|------|---|------|
| `ContextRetrievalService.java` | ContextRetrievalService | 拉取最近 N 条群消息上下文 |
| `ChatHistoryRetrievalService.java` | ChatHistoryRetrievalService | Style RAG：向量检索本人历史回复 + 风格多样性平衡 |
| `StyleTagger.java` | StyleTagger | 风格标签分类器（规则打标签：common/catchphrase/humor/rare） |
| `MemoryRagService.java` | MemoryRagService (接口) | 记忆 RAG 抽象 |
| `SimpleMemoryRagService.java` | SimpleMemoryRagService | 记忆 RAG 实现（关键词匹配） |

#### Style RAG（ChatHistoryRetrievalService）
- 从 `bot_chat_record` 查询本人真实发言（is_self=true, is_bot_reply=false）
- Embedding 向量化后存入 InMemoryVectorStore，同时打上 `style_type` 标签
- 检索时使用 **MMR（Max Marginal Relevance）** 重排，lambda=0.7
  - `MMR(d) = λ·Sim(d,Q) - (1-λ)·max Sim(d,d')`
  - 平衡相关性与多样性，防止同一话题霸占结果
- **风格多样性平衡**：限制特色表达（catchphrase/rare/humor）不超过结果的 30%
  - 超出部分替换为 common 类型候选，防止模型误以为“这个人就是这样讲话的”
- metadata 包含 `topic`（话题）和 `style_type`（风格标签）字段

#### Memory RAG（SimpleMemoryRagService）
- **双轨读取**：
  - `structuredMemories` 非空 → 新路径（过滤过期 + 按 importance 排序 + 按 type 分组）
  - `structuredMemories` 为空 → fallback 旧 tags/facts 逻辑
- **类型标签输出**：
  ```
  聊天对象「小明」
  【Identity】程序员，喜欢游戏
  【Relationship】大学朋友，亲密度8/10，喜欢互相调侃
  【Preference】Java, 杭州, LOL
  【Episode】养了一只猫叫小花
  ```

---

### 3.6 话题感知 — `ai/topic/`

| 文件 | 类 | 职责 |
|------|---|------|
| `TopicExtractor.java` | TopicExtractor (接口) | 话题提取抽象 |
| `SimpleTopicExtractor.java` | SimpleTopicExtractor | 规则实现（提取 2-4 字中文名词） |
| `ConversationState.java` | ConversationState | 单群对话状态 DTO |
| `ConversationStateManager.java` | ConversationStateManager | 对话状态管理器 |
| `TopicJudgeService.java` | TopicJudgeService (接口) | RAG 必要性判断抽象 |
| `SimpleTopicJudgeService.java` | SimpleTopicJudgeService | 规则实现（噪音/短消息跳过） |
| `AiReplyHistory.java` | AiReplyHistory | AI 回复历史追踪器 |

**话题锚定防御体系**（三道防线）：
1. **ConversationStateManager** — 追踪每群的话题连续提及次数，超过 topicStaleThreshold(3) 判定过热
2. **AiReplyHistory** — 追踪最近 20 条 AI 回复的话题标签，检测同一话题过度讨论
3. **反锚定提示注入** — 过热时向 Prompt 注入："当前话题已讨论较多，请跟随新主题"

**RAG 必要性判断**（节省 Embedding 调用）：
- 噪音词精确匹配 → 跳过
- 疑问词检测 → 需要检索
- 长度 > 4 → 需要检索
- 短消息无疑问词 → 跳过

---

### 3.7 对话摘要 — `ai/summary/`

| 文件 | 类 | 职责 |
|------|---|------|
| `ConversationSummaryService.java` | ConversationSummaryService (接口) | 摘要抽象 |
| `LlmConversationSummaryService.java` | LlmConversationSummaryService | Rule + LLM 混合实现 |

**Rule + LLM 混合策略**（控制 LLM 调用成本）：
1. 先 Rule 层分析每条消息质量
2. 全部噪音 → 返回"闲聊无主题"（不调 LLM）
3. 实质消息比例 < 30% 或数量 < 2 → Rule 拼接最后 3 条实质消息
4. 实质消息充足 → 调 LLM 生成摘要

**噪音判定**：50+ 个精确匹配词（哈哈/666/好的/嗯/哦...）
**缓存**：5 分钟 TTL（summaryCacheSeconds=300）

---

### 3.8 Prompt 构建 — `ai/prompt/`

| 文件 | 类 | 职责 |
|------|---|------|
| `PromptBuilder.java` | PromptBuilder | 三层 Prompt 架构构建器 |
| `SpeakingStyleExtractor.java` | SpeakingStyleExtractor | LLM 风格提炼器 |

#### 三层消息结构

```
SYSTEM 1 → 数字分身核心规则（身份/行为/风格统计/人格维度/风格表达原则/人格真实性/人格优先级/真实性/记忆原则/安全/输出）
SYSTEM 2 → 动态上下文（关于对方/最近聊天/风格样本[频率标注]/反锚定提示）
USER     → 当前微信消息
```

#### System Prompt 缓存机制
- 缓存 Key = hash(PROMPT_VERSION + personaName + styleDesc + jsonMode)
- 配置不变时复用，避免每次回复重建字符串

#### 人格决策优先级（6 级）
1. 当前明确表达
2. 稳定人格特征
3. 高频行为模式
4. 最近状态
5. 历史事件
6. 模型常识

#### SpeakingStyleExtractor
- 从 RAG 记录中调 LLM 提炼抽象风格描述（避免 few-shot 照搬具体名词）
- 缓存 30 分钟
- 敏感词黑名单脱敏（黄瓜/番茄/可乐...→"某东西"）

#### Prompt Version
- 常量 `PROMPT_VERSION = "v3.4"`
- 每次修改 Prompt 模板递增版本号，用于日志追踪和效果对比

#### 风格样本频率标注
- Style RAG 样本根据 `styleType` 自动添加频率标注：
  - `rare` → `[极少]`
  - `catchphrase/humor` → `[偶尔]`
  - `common` → 无标注
- 让模型直观感知每条样本的出现频率，避免将低频表达当作默认风格

---

### 3.9 审核层 — `ai/review/`

| 文件 | 类 | 职责 |
|------|---|------|
| `ReplyReviewService.java` | ReplyReviewService (接口) | 审核抽象 |
| `PassThroughReviewService.java` | PassThroughReviewService | 复合审核（空值 + PersonaValidator） |
| `PersonaValidator.java` | PersonaValidator | 人格漂移检测器 |
| `ReviewResult.java` | ReviewResult | 审核结果 DTO |

#### PersonaValidator 四维检测
1. **长度检查** — 超过 150 字则截取到句号/换行处
2. **Markdown 检查** — 检测 #/**/```/列表等标记并清洗
3. **AI 特征检查** — "作为AI"/"根据我的分析"/"首先...其次" 等模式 → 拒绝
4. **系统泄露检查** — "system prompt"/"记忆系统"/"persona" 等关键词 → 拒绝

**修正策略**：长度/格式问题可自动修正；AI用语/泄露则直接拒绝。

---

### 3.10 记忆引擎 — `ai/memory/`

| 文件 | 类 | 职责 |
|------|---|------|
| `MemoryEntry.java` | MemoryEntry | 结构化记忆条目（type/importance/confidence/TTL） |
| `ImportanceJudge.java` | ImportanceJudge | 重要性评分器（纯规则，零 LLM 调用） |
| `UserMemoryExtractor.java` | UserMemoryExtractor | LLM 提取 + 评分 + 存储 |
| `UserMemoryService.java` | UserMemoryService | 记忆 CRUD 服务 |

#### MemoryEntry 字段
```java
String id;              // UUID
String type;            // identity / preference / episode / relationship
String content;         // 记忆内容
double importance;      // 0-1
double confidence;      // 0-1
int ttlDays;            // 0=永久, 30=月度, 180=半年
LocalDateTime createdAt;
String source;          // 来源对话片段
String promptVersion;   // 生成时的 Prompt 版本
```

#### ImportanceJudge 评分规则
| 模式 | importance | TTL | 示例 |
|------|-----------|-----|------|
| 噪音词精确匹配 | 0.0 | 丢弃 | "哈哈" "666" "好的" |
| 临时状态 | 0.25 | 30天 | "今天好累" "最近忙" |
| 事实/经历 | 0.6 | 180天 | "我养了猫" "去了日本" |
| 关系 | 0.7 | 永久 | "小明是我大学室友" |
| 身份/价值观 | 0.85 | 永久 | "不想做Java了" "我是素食者" |

#### 写入流程
```
LLM 提取 candidates[] → ImportanceJudge 评分 → importance < 0.3 丢弃
→ 内容去重检查 → 构建 MemoryEntry → 存入 structuredMemories
→ 清理过期条目 → 按 importance 排序保留 maxEntries(50) 条
```

#### LLM 提取 Prompt 输出格式
```json
{
  "candidates": [
    {"content": "以后不想做Java开发了", "type": "identity", "importance": 0.9, "confidence": 0.88}
  ],
  "summary_update": "从Java开发者转为产品经理方向"
}
```

---

### 3.11 流水线 — `ai/pipeline/`

| 文件 | 类 | 职责 |
|------|---|------|
| `AiReplyPipeline.java` | AiReplyPipeline | 13 步异步编排器（@Async） |
| `AiReplyTracker.java` | AiReplyTracker | AI 回复指纹追踪（防回流到 RAG） |
| `PrivateMessageBuffer.java` | PrivateMessageBuffer | 私聊消息聚合器（Debounce） |

#### 13 步流水线详细流程
```
1.  ReplyDecisionService.decide()     — 决策是否回复
2.  TopicExtractor.extract()           — 提取话题关键词
3.  ConversationStateManager.update()  — 更新对话状态
4.  TopicJudgeService.needsRagLookup() — 判断是否需要 RAG
5.  ContextRetrievalService            — 拉取最近 15 条上下文
6.  ConversationSummaryService         — Rule+LLM 生成对话摘要
7.  ChatHistoryRetrievalService        — Style RAG 向量检索（条件）
8.  SimpleMemoryRagService             — Memory RAG 记忆检索（条件）
9.  AiReplyHistory.isTopicOverused()   — 话题过热检查
10. PromptBuilder.buildMessages()      — 三层 Prompt 构建
11. LlmClient.chat() + LlmReply.parse() — 调用 LLM（动态 temperature）+ JSON 解析
12. ReviewService.review()             — 空值 + PersonaValidator 审核
13. Thread.sleep(1-3s)                 — 模拟人类延迟
14. WechatApiService.sendText()        — 发送微信消息
15. AiReplyTracker.register()          — 注册回复指纹
16. AiReplyHistory.record()            — 记录回复历史
17. UserMemoryExtractor.extractAndUpdate() — 记忆提取 + 评分 + 存储
```

#### PrivateMessageBuffer（私聊消息聚合）
- 问题：用户习惯一句话拆多条发，每条都回会显得"抢话"
- 方案：Debounce 缓冲区，等 bufferSeconds(5s) 无新消息后合并触发

#### AiReplyTracker（防回流）
- SHA-256 指纹 + 60 秒 TTL
- 发送后注册指纹，Webhook 收到 is_self=true 时判断是否为 AI 生成

---

## 四、数据模型

### 4.1 bot_chat_record（聊天记录表）
| 字段 | 类型 | 说明 |
|------|------|------|
| msg_id + app_id | 联合唯一 | 防重复入库 |
| room_id | 群聊 wxid | xxx@chatroom |
| sender_wxid / sender_nick | 发送者 | |
| is_self | boolean | 是否本人发送 |
| is_bot_reply | boolean | 是否 AI 生成（不纳入 RAG） |
| content | TEXT | 清洗后纯文本 |
| processed | boolean | 是否已向量化 |

### 4.2 bot_user_memory（用户画像表）
| 字段 | 类型 | 说明 |
|------|------|------|
| wxid | 唯一 | 用户标识 |
| nickname | varchar | 群内昵称 |
| summary | TEXT | 综合画像描述 |
| tags | JSON | 标签数组（旧字段，兼容） |
| facts | JSON | 事实数组（旧字段，兼容） |
| structured_memories | JSON | MemoryEntry[] 结构化记忆（新） |
| relationship_type | varchar | 关系类型（新） |
| intimacy_score | int | 亲密度 1-10（新） |
| communication_style | varchar | 交流风格（新） |

---

## 五、配置参考

```yaml
ai:
  enabled: true
  llm:
    provider: qwen
    api-key: ${AI_LLM_API_KEY:}
    base-url: https://ws-nvivbrq9wdb6pam1.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
    model: qwen-plus
    temperature: 0.85
    max-tokens: 200
  embedding:
    model: text-embedding-v4
    dimensions: 1024
  reply:
    max-per-day: 10000
    cooldown-seconds: 1
    random-rate: 0.2
    only-at: false
    context-size: 15
    rag-top-k: 12
    topic-aware: true
    topic-stale-threshold: 3
    use-conversation-summary: true
    summary-cache-seconds: 300
    private-chat:
      random-rate: 1
      cooldown-seconds: 1
      buffer-seconds: 5
      question-rate: 1
  prompt:
    persona-name: ${AI_PERSONA_NAME:}
    custom-instructions: ""
    style-description: >
      （个人风格描述，口头禅/句式/禁忌等）
    anti-anchoring-hint: "当前话题已讨论较多，请跟随新主题。"
    json-reply-format: true          # 调试模式
    memory-importance-threshold: 0.3
    max-memory-entries: 50
    personality:
      humor-level: high
      sarcasm-level: medium
      casual-level: high
      warmth-level: low
    scene-temperature:
      normal: 0.7
      humor: 0.85
      question: 0.65
```

---

## 六、文件清单

```
ai/
├── decision/
│   ├── ReplyDecision.java              # 决策结果 DTO
│   └── ReplyDecisionService.java       # 多条件决策引擎
├── embedding/
│   ├── EmbeddingService.java           # Embedding 接口
│   └── OpenAiEmbeddingService.java     # OpenAI 兼容实现
├── llm/
│   ├── LlmClient.java                 # LLM 接口
│   ├── LlmMessage.java                # 消息 DTO
│   ├── LlmReply.java                  # 结构化回复 DTO + JSON 解析
│   └── OpenAiCompatibleClient.java    # OpenAI 兼容实现
├── memory/
│   ├── ImportanceJudge.java            # 重要性评分器（纯规则）
│   ├── MemoryEntry.java               # 结构化记忆条目
│   ├── UserMemoryExtractor.java        # LLM 提取 + 评分 + 存储
│   └── UserMemoryService.java          # 记忆 CRUD
├── pipeline/
│   ├── AiReplyPipeline.java            # 13 步异步编排器
│   ├── AiReplyTracker.java             # AI 回复指纹追踪
│   └── PrivateMessageBuffer.java       # 私聊消息聚合器
├── prompt/
│   ├── PromptBuilder.java              # 三层 Prompt 架构 (v3.4)
│   └── SpeakingStyleExtractor.java     # LLM 风格提炼器
├── retrieval/
│   ├── ChatHistoryRetrievalService.java # Style RAG（MMR 重排 + 多样性平衡）
│   ├── ContextRetrievalService.java     # 最近上下文拉取
│   ├── MemoryRagService.java           # Memory RAG 接口
│   ├── SimpleMemoryRagService.java     # Memory RAG 实现（双轨制）
│   └── StyleTagger.java               # 风格标签分类器（规则打标签）
├── review/
│   ├── PassThroughReviewService.java   # 复合审核服务
│   ├── PersonaValidator.java           # 人格漂移检测器
│   ├── ReplyReviewService.java         # 审核接口
│   └── ReviewResult.java              # 审核结果 DTO
├── summary/
│   ├── ConversationSummaryService.java # 摘要接口
│   └── LlmConversationSummaryService.java # Rule+LLM 混合摘要
├── topic/
│   ├── AiReplyHistory.java            # AI 回复历史追踪
│   ├── ConversationState.java          # 对话状态 DTO
│   ├── ConversationStateManager.java   # 对话状态管理器
│   ├── SimpleTopicExtractor.java       # 规则话题提取
│   ├── SimpleTopicJudgeService.java    # RAG 必要性判断
│   ├── TopicExtractor.java             # 话题提取接口
│   └── TopicJudgeService.java          # RAG 判断接口
└── vectorstore/
    ├── InMemoryVectorStore.java        # 内存向量存储
    ├── VectorDocument.java             # 文档 DTO
    ├── VectorSearchResult.java         # 搜索结果 DTO
    └── VectorStore.java               # 向量存储接口

entity/
├── BotChatRecord.java                  # 聊天记录表
└── BotUserMemory.java                  # 用户画像表

config/
└── AiProperties.java                   # AI 配置属性绑定
```

**共 38 个 Java 文件**，分布在 11 个子包中。

---

## 七、扩展路线图

| 阶段 | 功能 | 状态 |
|------|------|------|
| v1 | 基础 RAG + 简单 Prompt | ✅ 已完成 |
| v2 | 话题锚定防御 + Rule+LLM 摘要 + 对话状态 | ✅ 已完成 |
| v3 | 三层 Prompt 架构 + 人格优先级 + Memory 类型标签 | ✅ 已完成 |
| v3.2 | JSON 输出 + ImportanceJudge + TTL + PersonaValidator + 关系建模 | ✅ 已完成 |
| v3.4 | Style Suppression + 风格标签 + 动态 Temperature + 人格维度拆分 | ✅ 已完成 |
| v4 | Persona Decision Layer（两次 LLM：先分析再回复） | 🔲 规划中 |
| v5 | Style Learning Feedback Loop（根据互动反馈更新 style score） | 🔲 规划中 |
| v6 | Persona Evaluation Benchmark（测试集验证像不像本人） | 🔲 规划中 |
