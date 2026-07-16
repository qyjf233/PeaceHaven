package com.potato.peacehaven.ai.learning;

import com.potato.peacehaven.entity.PersonaStability;
import com.potato.peacehaven.entity.PersonaStyleSnapshot;
import com.potato.peacehaven.repository.PersonaStabilityRepository;
import com.potato.peacehaven.repository.PersonaStyleSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 人格漂移检测器（Persona Engine v4.1 — Phase 4）
 * <p>
 * 判断维度变化是真正的人格变化，还是只是场景切换导致的波动。
 * <br>
 * 规则：
 * <ul>
 *   <li>30天窗口内 global change > scene change → 认为人格变化</li>
 *   <li>global change < scene change → 只是场景切换，不改人格</li>
 *   <li>例：一个周末朋友聚会 sarcasm+0.3 → 不算人格变化</li>
 *   <li>例：30天所有场景 warmth 持续下降 → 真正变化</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonaDriftDetector {

    private final PersonaStabilityRepository stabilityRepo;
    private final PersonaStyleSnapshotRepository snapshotRepo;

    /** 漂移检测窗口（天） */
    private static final int DRIFT_WINDOW_DAYS = 30;

    /** 变化阈值：超过此值认为是显著变化 */
    private static final double CHANGE_THRESHOLD = 0.15;

    /**
     * 检测并更新人格稳定性
     * <p>
     * 对比当前学习值与 30 天内 snapshot 的历史均值，
     * 计算各维度变化幅度，更新 PersonaStability。
     * </p>
     *
     * @param currentHumor    当前学习到的 humor
     * @param currentSarcasm  当前学习到的 sarcasm
     * @param currentWarmth   当前学习到的 warmth
     */
    public void detectAndUpdateStability(double currentHumor, double currentSarcasm, double currentWarmth) {
        // 获取 30 天内 snapshot
        LocalDateTime windowStart = LocalDateTime.now().minusDays(DRIFT_WINDOW_DAYS);
        List<PersonaStyleSnapshot> snapshots = snapshotRepo.findByCreatedAtAfterOrderByCreatedAtAsc(windowStart);

        if (snapshots.size() < 2) {
            log.info("[DriftDetector] 快照不足 2 条({}), 跳过检测", snapshots.size());
            return;
        }

        // 计算历史均值
        double histHumor = snapshots.stream().mapToDouble(PersonaStyleSnapshot::getHumorScore).average().orElse(currentHumor);
        double histSarcasm = snapshots.stream().mapToDouble(PersonaStyleSnapshot::getSarcasmScore).average().orElse(currentSarcasm);
        double histWarmth = snapshots.stream().mapToDouble(PersonaStyleSnapshot::getWarmthScore).average().orElse(currentWarmth);

        // 计算变化幅度
        double humorDelta = Math.abs(currentHumor - histHumor);
        double sarcasmDelta = Math.abs(currentSarcasm - histSarcasm);
        double warmthDelta = Math.abs(currentWarmth - histWarmth);

        // stability = 1 - normalized delta（delta 越大，stability 越低）
        double humorStability = Math.max(0, 1.0 - humorDelta / CHANGE_THRESHOLD);
        double sarcasmStability = Math.max(0, 1.0 - sarcasmDelta / CHANGE_THRESHOLD);
        double warmthStability = Math.max(0, 1.0 - warmthDelta / CHANGE_THRESHOLD);

        // 持久化
        PersonaStability stability = stabilityRepo.findById(1L).orElse(
                PersonaStability.builder().id(1L).build()
        );
        stability.setHumorStability(humorStability);
        stability.setSarcasmStability(sarcasmStability);
        stability.setWarmthStability(warmthStability);
        stabilityRepo.save(stability);

        log.info("[DriftDetector] stability updated: humor={}, sarcasm={}, warmth={} (deltas: h={}, s={}, w={})",
                String.format("%.2f", humorStability),
                String.format("%.2f", sarcasmStability),
                String.format("%.2f", warmthStability),
                String.format("%.3f", humorDelta),
                String.format("%.3f", sarcasmDelta),
                String.format("%.3f", warmthDelta));
    }

    /**
     * 计算 effectiveConfidence（Stability 控制学习速度）
     * <p>
     * effectiveConfidence = learningConfidence * (1 - stabilityPenalty)
     * <ul>
     *   <li>稳定人格(stability=0.7): ec = 0.8 * 0.3 = 0.24 → 只改变 24%</li>
     *   <li>确实在变(stability=0.1): ec = 0.8 * 0.9 = 0.72 → 允许快速变化</li>
     * </ul>
     * </p>
     *
     * @param learningConfidence 学习置信度
     * @param dimension          维度名（humor/sarcasm/warmth）
     * @return effectiveConfidence
     */
    public double computeEffectiveConfidence(double learningConfidence, String dimension) {
        PersonaStability stability = stabilityRepo.findById(1L).orElse(null);
        if (stability == null) {
            return learningConfidence; // 无历史数据，不惩罚
        }

        double stabilityPenalty = switch (dimension) {
            case "humor" -> stability.getHumorStability();
            case "sarcasm" -> stability.getSarcasmStability();
            case "warmth" -> stability.getWarmthStability();
            default -> 0.5;
        };

        return learningConfidence * (1.0 - stabilityPenalty);
    }

    /**
     * 判断全局变化是否超过场景变化（Drift 核心判断）
     * <p>
     * 如果 global change > scene change → 真正人格变化
     * 如果 global change <= scene change → 场景切换，不改人格
     * </p>
     *
     * @param globalDelta 全局维度变化幅度
     * @param sceneDelta  场景维度变化幅度（各场景加权均值）
     * @return true=人格变化, false=场景切换
     */
    public boolean isGlobalDrift(double globalDelta, double sceneDelta) {
        return globalDelta > sceneDelta && globalDelta > CHANGE_THRESHOLD;
    }
}
