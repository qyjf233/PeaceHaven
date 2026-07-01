package com.potato.peacehaven.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

@Entity
@Table(name = "activity_vote_option")
@Comment("投票选项表")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoteOption {

    /** 选项ID，自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("投票选项ID")
    private Long id;

    /** 所属活动，多对一关联 activity 表 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id", nullable = false)
    @Comment("所属活动ID")
    private Activity activity;

    /** 选项名称，如候选人、作品名等 */
    @Column(name = "option_name", nullable = false, length = 100)
    @Comment("选项名称")
    private String optionName;

    /** 选项配图URL，可选（如作品截图） */
    @Column(name = "option_image", length = 500)
    @Comment("选项配图URL")
    private String optionImage;

    /** 当前累计票数，默认0 */
    @Column(name = "vote_count", nullable = false)
    @Builder.Default
    @Comment("当前累计票数")
    private Integer voteCount = 0;

    /** 显示排序，数值越小越靠前，默认0 */
    @Column(name = "sort_order")
    @Builder.Default
    @Comment("显示排序，升序排列")
    private Integer sortOrder = 0;
}
