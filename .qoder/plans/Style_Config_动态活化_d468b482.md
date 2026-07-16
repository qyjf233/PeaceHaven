# Style Config 动态活化

## 核心思路

静态 YAML 配置作为"冷启动种子"，系统积累足够数据后，通过定时任务自动学习并覆盖。优先级链：
**学习配置(DB) > 静态配置(YAML) > 默认值**

## Task 1: LearnedStyleConfig 实体 + Repository

新建 `entity/LearnedStyleConfig.java`：

```java
@Entity @Table(name = "learned_style_config")
public class LearnedStyleConfig {
    @Id private Long id = 1L;           // 单行表，固定 id=1
    private String styleDescription;     // LLM 提炼的风格描述
    private String humorLevel;           // high/medium/low（从标签统计计算）
    private String sarcasmLevel;
    private String casualLevel;
    private String warmthLevel;
    private double avgConfidence;        // LLM 平均置信度
    private int sampleCount;             // 学习时使用的消息数量
    private int version;                 // 学习版本号
    private LocalDateTime updatedAt;
}
```

新建 `repository/LearnedStyleConfigRepository.java`。

## Task 2: StyleLearningService 定时学习

新建 `ai/learning/StyleLearningService.java`：

**定时触发**：每 6 小时一次（`@Scheduled`），且需要至少 50 条本人消息才启动学习。

**学习流程**：
1. 从 `bot_chat_record` 查最近 200 条 `is_self=true AND is_bot_reply=false` 的记录
2. **统计人格维度**：用 StyleTagger 对每条消息打标签，计算各类型占比：
   - `humor_ratio = humor_count / total` -> 映射 humorLevel
   - `catchphrase_ratio = catchphrase_count / total` -> 映射 sarcasmLevel
   - `avg_length` -> 映射 casualLevel（短=high，长=low）
   - `warmth_ratio`（正面词检测）-> 映射 warmthLevel
3. **LLM 提炼风格描述**：取 30 条样本消息，调 LLM 生成风格描述（复用 SpeakingStyleExtractor 的逻辑但输出更长更精确）
4. **持久化**：保存到 `learned_style_config` 表，version+1
5. **日志**：记录学习前后的关键指标变化

**配置阈值**（application.yaml）：
```yaml
ai:
  learning:
    enabled: true
    interval-hours: 6
    min-messages: 50
```

## Task 3: ConfidenceTracker — 置信度追踪

新建 `ai/learning/ConfidenceTracker.java`：

- 内存滑动窗口（最近 100 条回复的 confidence）
- `AiReplyPipeline` 在 LLM 返回 confidence 时调用 `tracker.record(confidence)`
- 暴露 `getAvgConfidence()` 和 `getLowConfidenceRatio()`
- `StyleLearningService` 在定时学习时读取并写入 `learned_style_config.avg_confidence`
- 后续可用于动态 temperature：confidence 低时适当提高 temperature

## Task 4: PromptBuilder 集成学习配置

修改 `ai/prompt/PromptBuilder.java`：

- 注入 `LearnedStyleConfigRepository`
- `resolveCurrentStyleDesc()` 改为：先查 DB 的 learnedStyleDescription，有则用；无则 fallback YAML
- `resolvePersonality()` 同理：先查 DB 的学习维度，有则用；无则 fallback YAML
- 缓存 key 增加 learnedVersion，学习更新后自动触发 Prompt 重建

## Task 5: application.yaml 增加学习配置

```yaml
ai:
  learning:
    enabled: true              # 动态学习总开关
    interval-hours: 6          # 学习间隔（小时）
    min-messages: 50           # 最少消息数才启动学习
    max-samples: 200           # 每次学习取最近 N 条消息
    style-extract-samples: 30  # 给 LLM 提炼风格的样本数
```

## Task 6: AiProperties 增加 LearningConfig

修改 `config/AiProperties.java`，新增：
```java
private LearningConfig learning = new LearningConfig();

public static class LearningConfig {
    private boolean enabled = false;
    private int intervalHours = 6;
    private int minMessages = 50;
    private int maxSamples = 200;
    private int styleExtractSamples = 30;
}
```

## Task 7: AiReplyPipeline 集成 ConfidenceTracker

修改 `ai/pipeline/AiReplyPipeline.java`：
- 注入 `ConfidenceTracker`
- 在 LLM 返回 confidence 时调用 `tracker.record(parsed.getConfidence())`

## Task 8: 编译验证

## 数据流总结

```
你在群里聊天
    |
    v
bot_chat_record (is_self=true) -- 自动采集
    |
    v [每6小时]
StyleLearningService
    |-- StyleTagger 统计 -> personality 维度
    |-- LLM 提炼 -> style-description
    |-- ConfidenceTracker -> avg_confidence
    |
    v
learned_style_config (DB)
    |
    v [每次回复]
PromptBuilder (优先用学习配置，fallback YAML)
    |
    v
LLM 回复 -> confidence -> ConfidenceTracker
```
