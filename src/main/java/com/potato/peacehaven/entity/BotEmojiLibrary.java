package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 表情包特征库
 * <p>
 * 存储所有经过的表情包（type=47），记录其 MD5、文件大小、使用场景标签等。
 * <ul>
 *   <li>md5 为表情包唯一标识（微信协议层用 MD5 定位表情资源）</li>
 *   <li>emojiSize 为文件大小（发送表情接口 postEmoji 必须参数）</li>
 *   <li>tags / description 由 LLM 根据上下文批量标注生成</li>
 *   <li>contextSamples 存储采集到的使用场景（JSON 数组），供 LLM 标注时参考</li>
 * </ul>
 */
@Entity
@Table(name = "bot_emoji_library",
        indexes = {
                @Index(name = "idx_bel_tags", columnList = "tags"),
                @Index(name = "idx_bel_labeled", columnList = "labeled"),
                @Index(name = "idx_bel_usage_count", columnList = "usage_count")
        })
@Comment("表情包特征库（type=47 表情包语义标注）")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotEmojiLibrary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 表情包 MD5（微信协议唯一标识，也是 postEmoji 接口的 emojiMd5 参数） */
    @Column(name = "md5", nullable = false, unique = true, length = 64)
    @Comment("表情包 MD5（唯一标识）")
    private String md5;

    /** 表情文件大小（postEmoji 接口的 emojiSize 参数） */
    @Column(name = "emoji_size", nullable = false)
    @Comment("表情文件大小（字节）")
    private Integer emojiSize;

    /** 表情类型（XML 中 emoji.type：1=内置 / 2=自定义等） */
    @Column(name = "emoji_type")
    @Comment("表情类型（1=内置 2=自定义）")
    private Integer emojiType;

    /** 表情宽度（像素） */
    @Column(name = "width")
    @Comment("表情宽度")
    private Integer width;

    /** 表情高度（像素） */
    @Column(name = "height")
    @Comment("表情高度")
    private Integer height;

    /** 表情包产品 ID（官方表情包有，自定义表情无） */
    @Column(name = "product_id", length = 200)
    @Comment("表情包产品 ID")
    private String productId;

    /** CDN URL（备用，部分场景可从此下载表情图片） */
    @Column(name = "cdn_url", length = 1000)
    @Comment("CDN URL")
    private String cdnUrl;

    /** 使用次数（每收到一次该表情 +1） */
    @Column(name = "usage_count", nullable = false)
    @Builder.Default
    @Comment("使用次数")
    private Integer usageCount = 1;

    /** LLM 生成的标签（逗号分隔，如"无语,摆烂,躺平"） */
    @Column(name = "tags", length = 500)
    @Comment("LLM 生成的语义标签")
    private String tags;

    /** LLM 生成的自然语言描述（如"表达无奈、躺平、不想说话的意思"） */
    @Column(name = "description", length = 500)
    @Comment("LLM 生成的语义描述")
    private String description;

    /**
     * 采集到的上下文样本（JSON 数组）
     * <p>
     * 每个样本包含：发送者、前后消息摘要、对话主题等。
     * 最多保留 10 条样本，超出后 FIFO 淘汰旧的。
     */
    @Column(name = "context_samples", columnDefinition = "TEXT")
    @Comment("上下文样本（JSON 数组）")
    private String contextSamples;

    /** 是否已完成 LLM 标注 */
    @Column(name = "labeled", nullable = false)
    @Builder.Default
    @Comment("是否已标注")
    private Boolean labeled = false;

    /** 首次入库时间 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("首次入库时间")
    private LocalDateTime createdAt;

    /** 最近更新时间（每次使用或标注后刷新） */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    @Comment("最近更新时间")
    private LocalDateTime updatedAt;
}
