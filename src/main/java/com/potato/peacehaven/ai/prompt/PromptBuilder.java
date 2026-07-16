package com.potato.peacehaven.ai.prompt;

import com.potato.peacehaven.ai.llm.LlmMessage;
import com.potato.peacehaven.ai.retrieval.ChatHistoryRetrievalService.RetrievedRecord;
import com.potato.peacehaven.ai.retrieval.ContextRetrievalService.ContextMessage;
import com.potato.peacehaven.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Prompt 构建器
 * <p>
 * 按照预设模板组装完整的 messages 列表（system + user），
 * 包含角色扮演指令、用户画像、RAG 历史回复、最近上下文等。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptBuilder {

    private final AiProperties aiProps;
    private final SpeakingStyleExtractor styleExtractor;

    /**
     * 构建完整的 LLM messages
     *
     * @param senderNick    发送者昵称
     * @param currentMessage 当前消息内容
     * @param userMemoryText 用户画像文本（已由 UserMemoryService 格式化）
     * @param ragRecords     RAG 检索的本人历史回复
     * @param contextMessages 最近上下文消息
     * @return LLM messages 列表
     */
    public List<LlmMessage> buildMessages(
            String senderNick,
            String currentMessage,
            String userMemoryText,
            List<RetrievedRecord> ragRecords,
            List<ContextMessage> contextMessages) {

        List<LlmMessage> messages = new ArrayList<>();

        // ===== System Message 1: 核心角色指令 =====
        String personaName = aiProps.getPrompt().getPersonaName();
        if (personaName == null || personaName.isBlank()) {
            personaName = "目标用户";
        }

        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("你现在扮演微信用户「").append(personaName).append("」，在群聊中模仿 TA 的真实聊天风格回复消息。\n\n");
        systemPrompt.append("核心要求：\n");
        systemPrompt.append("- 回复要自然、口语化，像一个真人在微信群聊天\n");
        systemPrompt.append("- 不要像 AI，不要解释你为什么这样回答\n");
        systemPrompt.append("- 回复尽量简短（1-3句话），不要长篇大论\n");
        systemPrompt.append("- 按照下方的「风格描述」来组织语言，模仿其中的语气、句式和表达习惯\n");
        systemPrompt.append("- 当涉及专业知识时，可结合模型知识补充，但仍需保持本人风格\n");
        systemPrompt.append("- 直接输出回复内容，不要加任何前缀、引号或说明\n");
        systemPrompt.append("- 回复内容应针对当前对话，不要提及风格描述或历史记录中的任何具体事物\n");

        // 追加用户画像
        if (userMemoryText != null && !userMemoryText.isBlank()) {
            systemPrompt.append("\n").append(userMemoryText);
        }

        // 追加自定义指令
        String customInstructions = aiProps.getPrompt().getCustomInstructions();
        if (customInstructions != null && !customInstructions.isBlank()) {
            systemPrompt.append("\n").append(customInstructions);
        }

        messages.add(LlmMessage.system(systemPrompt.toString()));

        // ===== System Message 2: 风格描述（从 RAG 记录提炼，不含具体名词） =====
        String styleDesc = styleExtractor.getStyleDescription(ragRecords);
        if (styleDesc != null && !styleDesc.isBlank()) {
            messages.add(LlmMessage.system(
                    "以下是根据本人历史聊天提炼的风格描述，请按此风格回复：\n" + styleDesc));
        }

        // ===== System Message 3: 最近上下文 =====
        String contextText = formatContextMessages(contextMessages);
        if (contextText != null && !contextText.isBlank()) {
            messages.add(LlmMessage.system(
                    "以下是群里最近的聊天记录：\n" + contextText));
        }

        // ===== User Message: 当前消息 =====
        String nick = (senderNick != null && !senderNick.isBlank()) ? senderNick : "群友";
        messages.add(LlmMessage.user(nick + ": " + currentMessage));

        return messages;
    }

    /**
     * 格式化最近上下文
     */
    private String formatContextMessages(List<ContextMessage> messages) {
        if (messages == null || messages.isEmpty()) return "";

        return messages.stream()
                .filter(m -> m.getContent() != null && !m.getContent().isBlank())
                .map(m -> {
                    String prefix = m.isSelf() ? "我" : m.getSenderNick();
                    return prefix + ": " + m.getContent();
                })
                .collect(Collectors.joining("\n"));
    }
}
