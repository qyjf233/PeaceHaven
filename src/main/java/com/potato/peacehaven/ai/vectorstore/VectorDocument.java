package com.potato.peacehaven.ai.vectorstore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 向量存储文档
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorDocument {

    /** 文档唯一标识（如 bot_chat_record.id） */
    private String id;

    /** 向量表示 */
    private float[] vector;

    /** 元数据（如 sender_wxid, is_self, content 等） */
    private Map<String, String> metadata;
}
