# AI 分身系统实现方案

## 架构概览

```
收到群消息
  -> ReplyDecisionService 决策（是否回复）
  -> ContextRetrievalService 拉取最近 15 条上下文
  -> ChatHistoryRetrievalService RAG 检索本人历史回复 Top-K
  -> UserMemoryService 加载聊天对象画像
  -> PromptBuilder 组装完整 Prompt
  -> LlmClient 调用大模型
  -> ReplyReviewService 审核（预留接口，默认放行）
  -> WechatApiService.postText 发送消息
```

新增包结构 `com.potato.peacehaven.ai.*`，与现有 `service/controller/entity` 平级。

---

## Phase 1: 配置中心 + LLM 模块

### 1.1 配置类

**`config/AiProperties.java`** - 绑定 `application.yaml` 的 `ai.*` 配置：
```yaml
ai:
  enabled: false                          # 总开关
  llm:
    provider: openai                      # 标识（仅日志用）
    api-key: ${AI_LLM_API_KEY:}
    base-url: ${AI_LLM_BASE_URL:}         # 如 https://api.deepseek.com/v1
    model: ${AI_LLM_MODEL:deepseek-chat}
    temperature: 0.85
    max-tokens: 200
  embedding:
    api-key: ${AI_EMBED_API_KEY:}         # 可复用 LLM key
    base-url: ${AI_EMBED_BASE_URL:}
    model: ${AI_EMBED_MODEL:text-embedding-3-small}
    dimensions: 1536
  reply:
    max-per-day: 50                       # 每日回复上限
    cooldown-seconds: 30                  # 同群最短回复间隔
    random-rate: 0.15                     # 无触发条件时随机回复概率
    only-at: false                        # true=仅@时才回复
    context-size: 15                      # 上下文条数
    rag-top-k: 8                          # RAG 检索条数
  prompt:
    persona-name: ${AI_PERSONA_NAME:}     # 扮演的用户名
    custom-instructions: ""               # 额外提示词（追加到 system prompt）
```

### 1.2 LLM 客户端

**`ai/llm/LlmClient.java`** - 接口：
```java
public interface LlmClient {
    String chat(List<LlmMessage> messages, Double temperature, Integer maxTokens);
}
```

**`ai/llm/LlmMessage.java`** - DTO（role + content）。

**`ai/llm/OpenAiCompatibleClient.java`** - 实现类：
- 使用 Spring `RestClient` 调用 `/v1/chat/completions`
- 支持 stream=false 的同步模式
- 超时配置 30s，异常时返回 null + 日志告警
- 请求体格式：`{ model, messages, temperature, max_tokens, stream: false }`

### 1.3 Embedding 服务

**`ai/embedding/EmbeddingService.java`** - 接口：
```java
public interface EmbeddingService {
    float[] embed(String text);
    float[][] embedBatch(List<String> texts);
}
```

**`ai/embedding/OpenAiEmbeddingService.java`** - 实现类：
- 调用 `/v1/embeddings`，返回 float[] 向量
- 批量接口减少 API 调用次数

---

## Phase 2: RAG 向量检索模块

### 2.1 VectorStore 统一接口

**`ai/vectorstore/VectorStore.java`** - 核心接口（后续可切换 PGVector/Milvus/Qdrant）：
```java
public interface VectorStore {
    void upsert(String id, float[] vector, Map<String, String> metadata);
    void upsertBatch(List<VectorDocument> documents);
    List<VectorSearchResult> search(float[] query, int topK, Map<String, String> filters);
    void delete(String id);
    void deleteByMetadata(String key, String value);
}
```

**`ai/vectorstore/VectorDocument.java`** - 存储文档（id + vector + metadata）。
**`ai/vectorstore/VectorSearchResult.java`** - 搜索结果（id + score + metadata）。

### 2.2 InMemoryVectorStore 实现

**`ai/vectorstore/InMemoryVectorStore.java`** - 起步实现：
- `ConcurrentHashMap<String, VectorDocument>` 存储
- 余弦相似度计算
- 适合万级数据量，后续可无缝切换到 PGVector

### 2.3 聊天记录向量化服务

**`ai/retrieval/ChatHistoryRetrievalService.java`**：
- `indexNewRecords()` - 查询 `bot_chat_record` 中 `processed=false` 且 `is_self=true` 的记录，批量 embedding + 存入 VectorStore，标记 processed=true
- `retrieve(String currentMessage, int topK)` - 将当前消息 embedding，在 VectorStore 中检索本人历史回复（metadata 过滤 `is_self=true`），返回 Top-K 条
- metadata 字段：`sender_wxid`, `is_self`, `content`, `create_time`, `sender_nick`
- 建议配合定时任务（@Scheduled 每 10 分钟）自动索引新记录

---

## Phase 3: 决策模块

**`ai/decision/ReplyDecisionService.java`**：
- 核心方法：`ReplyDecision decide(String chatroomId, String senderWxid, String content, boolean isMentioned)`
- 返回 `ReplyDecision`（shouldReply + reason）
- 决策优先级：
  1. 被 @提及 -> 必须回复
  2. 配置 `only-at=true` -> 不回复
  3. 检测到提问（问号、疑问词） -> 高概率回复
  4. 距离上次回复超过 cooldown -> 可随机触发
  5. 随机概率 `random-rate` -> 按概率回复
  6. 每日上限 `max-per-day` -> 超限不回复
- 内部维护 `AtomicInteger dailyCount` + `Map<String, Long> lastReplyTime`，每日零点重置

---

## Phase 4: Prompt 构建 + 长期记忆

### 4.1 长期记忆表

**`entity/BotUserMemory.java`**：
```
bot_user_memory 表：
- id (PK)
- wxid (唯一)
- nickname (群昵称)
- summary (综合画像描述)
- tags (JSON 数组: ["Java", "杭州", "LOL"])
- facts (JSON 数组: ["养了一只猫叫小花", "喜欢深夜写代码"])
- updated_at
- created_at
```

**`repository/BotUserMemoryRepository.java`** - JPA 仓储。

**`ai/memory/UserMemoryService.java`**：
- `getUserMemory(wxid)` - 查询用户画像
- `formatMemoriesForPrompt(List<BotUserMemory>)` - 格式化为 prompt 片段

### 4.2 Prompt 构建器

**`ai/prompt/PromptBuilder.java`** - 核心组件，组装完整 messages 列表：

```
[System Message]
你现在扮演微信用户「{personaName}」，在群聊中模仿 TA 的真实聊天风格回复消息。

核心要求：
- 回复要自然、口语化，像一个真人在微信群聊天
- 不要像 AI，不要解释你为什么这样回答
- 回复尽量简短（1-3句话），不要长篇大论
- 优先模仿本人的语气、措辞、口头禅和聊天习惯
- 参考提供的历史聊天记录学习本人的说话方式
- 当涉及专业知识时，可结合模型知识补充，但仍需保持本人风格

聊天对象信息：
{userMemories}

{customInstructions}

[System Message 2 - 历史风格示例]
以下是本人过去的真实聊天记录，请学习说话风格：
{ragResults - Top K 条本人历史回复}

[System Message 3 - 最近上下文]
以下是群里最近的聊天记录：
{recentContext - 最近 N 条群消息}

[User Message]
{senderNick}: {currentMessage}
```

**`ai/retrieval/ContextRetrievalService.java`**：
- `getRecentContext(chatroomId, limit)` - 从 `bot_chat_record` 查询最近 N 条消息，格式化为 "发送者: 内容"

---

## Phase 5: 审核接口 + 流水线编排

### 5.1 回复审核

**`ai/review/ReplyReviewService.java`** - 接口（预留）：
```java
public interface ReplyReviewService {
    ReviewResult review(String originalMessage, String aiReply);
}
```

**`ai/review/PassThroughReviewService.java`** - 默认实现（直接放行）：
- 后续可替换为：敏感词过滤、LLM 二次审核、人工审核队列

### 5.2 流水线编排

**`ai/pipeline/AiReplyPipeline.java`** - 核心编排器：
```java
public void processGroupMessage(String chatroomId, String senderWxid,
                                 String senderNick, String content,
                                 boolean isMentioned) {
    // 1. 决策
    // 2. 并行拉取：上下文 + RAG + 用户记忆
    // 3. 构建 Prompt
    // 4. 调用 LLM
    // 5. 审核
    // 6. 发送（WechatApiService.postText）
    // 7. 更新决策统计（dailyCount++, lastReplyTime）
}
```
- 整个流程异步执行（`@Async` 或 `CompletableFuture`），不阻塞 webhook 3s 超时
- 发送消息前随机延迟 1-3s（模拟人类反应时间）

---

## Phase 6: Webhook 集成 + 管理端

### 6.1 Webhook 集成

修改 `WechatApiWebhookService.handleTextMessage()`：
```java
// 目标群聊文本消息
if (props.getGroupId().equals(chatroomId)) {
    saveChatRecord(event, pureContent);  // 已有
    aiReplyPipeline.processGroupMessage(     // 新增
        chatroomId, groupSender, senderNick,
        pureContent, mentioned);
}
```

### 6.2 管理后台（可选，后续迭代）

- 在机器人配置页增加 "AI 分身" 面板
- 展示：今日回复次数、RAG 索引进度、用户记忆列表
- 支持：编辑 persona 配置、手动触发索引、查看 AI 回复日志

---

## 实施顺序

| 步骤 | 内容 | 预估文件数 |
|------|------|-----------|
| 1 | 配置类 AiProperties + YAML | 2 文件 |
| 2 | LLM 客户端 + Embedding 服务 | 5 文件 |
| 3 | VectorStore 接口 + InMemory 实现 | 4 文件 |
| 4 | ChatHistoryRetrievalService + 定时索引 | 2 文件 |
| 5 | ReplyDecisionService 决策模块 | 2 文件 |
| 6 | UserMemory 实体 + 服务 | 3 文件 |
| 7 | ContextRetrievalService + PromptBuilder | 2 文件 |
| 8 | ReplyReviewService 接口 + 默认实现 | 3 文件 |
| 9 | AiReplyPipeline 流水线编排 | 1 文件 |
| 10 | Webhook 集成 + 异步配置 | 2 文件 |
| 11 | pom.xml 依赖（如有需要） | 1 文件 |

总计新增约 27 个文件，修改 2-3 个现有文件。

---

## 关键设计决策

1. **HTTP 客户端**：使用 Spring Boot 4.x 自带的 `RestClient`（无需额外依赖）
2. **向量存储起步**：InMemoryVectorStore（ConcurrentHashMap + 余弦相似度），数据量超万级时切换 PGVector
3. **异步执行**：Pipeline 整体异步，避免阻塞 webhook 3s 响应超时
4. **模拟真人**：发送前随机延迟 1-3s，回复长度控制在 1-3 句
5. **RAG 优先本人回复**：向量检索时过滤 `is_self=true`，确保 few-shot 示例全部来自本人
6. **审核接口预留**：`ReplyReviewService` 接口 + PassThrough 默认实现，后续可插入敏感词/LLM 二次审核
