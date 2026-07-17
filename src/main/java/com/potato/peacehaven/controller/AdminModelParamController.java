package com.potato.peacehaven.controller;

import com.potato.peacehaven.ai.memory.MemoryEntry;
import com.potato.peacehaven.ai.prompt.PromptBuilder;
import com.potato.peacehaven.entity.*;
import com.potato.peacehaven.repository.*;
import com.potato.peacehaven.service.AdminOperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 模型参数管理 Controller
 * <p>
 * 提供对所有影响 bot 回复行为的持久化参数的增删改查接口。
 * 所有操作记录到操作日志。
 * </p>
 */
@Slf4j
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminModelParamController {

    private final BotUserMemoryRepository userMemoryRepo;
    private final LearnedStyleConfigRepository styleConfigRepo;
    private final ExpressionProfileRepository expressionRepo;
    private final ExpressionSceneUsageRepository expressionSceneRepo;
    private final RelationshipProfileRepository relationshipRepo;
    private final SceneProfileRepository sceneRepo;
    private final CurrentStateProfileRepository currentStateRepo;
    private final PersonaStabilityRepository stabilityRepo;
    private final PersonaStyleSnapshotRepository snapshotRepo;
    private final AdminOperationLogService logService;
    private final PromptBuilder promptBuilder;

    // ===== 页面渲染 =====

    @GetMapping("/model-params")
    public String page() {
        return "admin/model-params";
    }

    // ========================================================================
    //  Tab 1: 用户画像 (bot_user_memory) — 多行 CRUD
    // ========================================================================

    @GetMapping("/api/model-params/user-memory")
    @ResponseBody
    public List<Map<String, Object>> listUserMemory() {
        return userMemoryRepo.findAll().stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", m.getId());
            map.put("wxid", m.getWxid());
            map.put("nickname", m.getNickname());
            map.put("summary", m.getSummary());
            map.put("relationshipType", m.getRelationshipType());
            map.put("intimacyScore", m.getIntimacyScore());
            map.put("communicationStyle", m.getCommunicationStyle());
            map.put("tags", m.getTags());
            map.put("facts", m.getFacts());
            map.put("structuredMemoriesCount", m.getStructuredMemories() != null ? m.getStructuredMemories().size() : 0);
            map.put("structuredMemories", m.getStructuredMemories());
            map.put("updatedAt", m.getUpdatedAt());
            map.put("createdAt", m.getCreatedAt());
            return map;
        }).collect(Collectors.toList());
    }

    @PostMapping("/api/model-params/user-memory")
    @ResponseBody
    @Transactional
    public Map<String, Object> createUserMemory(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String wxid = (String) body.get("wxid");
        if (wxid == null || wxid.isBlank()) return Map.of("success", false, "message", "wxid 不能为空");
        if (userMemoryRepo.findByWxid(wxid).isPresent()) return Map.of("success", false, "message", "该 wxid 已存在");

        BotUserMemory m = BotUserMemory.builder().wxid(wxid).build();
        applyUserMemoryFields(m, body);
        userMemoryRepo.save(m);
        logService.record("模型参数", "新增用户画像", "wxid=" + wxid, request);
        return Map.of("success", true, "id", m.getId());
    }

    @PutMapping("/api/model-params/user-memory/{id}")
    @ResponseBody
    @Transactional
    public Map<String, Object> updateUserMemory(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        BotUserMemory m = userMemoryRepo.findById(id).orElse(null);
        if (m == null) return Map.of("success", false, "message", "记录不存在");
        applyUserMemoryFields(m, body);
        userMemoryRepo.save(m);
        logService.record("模型参数", "编辑用户画像", "id=" + id + " wxid=" + m.getWxid(), request);
        return Map.of("success", true);
    }

    @DeleteMapping("/api/model-params/user-memory/{id}")
    @ResponseBody
    @Transactional
    public Map<String, Object> deleteUserMemory(@PathVariable Long id, HttpServletRequest request) {
        safeDelete(userMemoryRepo, id);
        logService.record("模型参数", "删除用户画像", "id=" + id, request);
        return Map.of("success", true);
    }

    @SuppressWarnings("unchecked")
    private void applyUserMemoryFields(BotUserMemory m, Map<String, Object> body) {
        if (body.containsKey("nickname")) m.setNickname(str(body.get("nickname")));
        if (body.containsKey("summary")) m.setSummary(str(body.get("summary")));
        if (body.containsKey("relationshipType")) m.setRelationshipType(str(body.get("relationshipType")));
        if (body.containsKey("intimacyScore")) m.setIntimacyScore(toInt(body.get("intimacyScore")));
        if (body.containsKey("communicationStyle")) m.setCommunicationStyle(str(body.get("communicationStyle")));
        if (body.containsKey("tags")) m.setTags(toStringList(body.get("tags")));
        if (body.containsKey("facts")) m.setFacts(toStringList(body.get("facts")));
        // structuredMemories: JSON 数组，直接透传到实体
        if (body.containsKey("structuredMemories") && body.get("structuredMemories") instanceof List) {
            List<Map<String, Object>> rawList = (List<Map<String, Object>>) body.get("structuredMemories");
            List<MemoryEntry> entries = rawList.stream().map(raw -> {
                // 使用 builder 确保 @Builder.Default 生效
                MemoryEntry.MemoryEntryBuilder b = MemoryEntry.builder();
                // ID: 已有则保留，无则 builder 自动生成 UUID
                String rawId = raw.get("id") != null ? raw.get("id").toString() : null;
                if (rawId != null && !rawId.isBlank() && !"自动生成".equals(rawId)) {
                    b.id(rawId);
                }
                b.type(str(raw.get("type")));
                b.content(str(raw.get("content")));
                b.importance(toDouble(raw.get("importance")));
                b.confidence(toDouble(raw.get("confidence")));
                b.ttlDays(toInt(raw.get("ttlDays")));
                if (raw.get("createdAt") != null) {
                    b.createdAt((long) toDouble(raw.get("createdAt")));
                }
                b.source(str(raw.get("source")));
                b.promptVersion(str(raw.get("promptVersion")));
                return b.build();
            }).collect(Collectors.toList());
            m.setStructuredMemories(entries);
        }
    }

    // ========================================================================
    //  Tab 2: 人格维度 (learned_style_config) — 单行 id=1
    // ========================================================================

    @GetMapping("/api/model-params/style-config")
    @ResponseBody
    public Map<String, Object> getStyleConfig() {
        LearnedStyleConfig c = styleConfigRepo.findById(1L).orElse(null);
        if (c == null) return Map.of("exists", false);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("exists", true);
        map.put("humorScore", c.getHumorScore());
        map.put("sarcasmScore", c.getSarcasmScore());
        map.put("casualScore", c.getCasualScore());
        map.put("warmthScore", c.getWarmthScore());
        map.put("formalScore", c.getFormalScore());
        map.put("slangScore", c.getSlangScore());
        map.put("avgLength", c.getAvgLength());
        map.put("lengthVariance", c.getLengthVariance());
        map.put("expressionVariance", c.getExpressionVariance());
        map.put("intimacyHumor", c.getIntimacyHumor());
        map.put("empathyHidden", c.getEmpathyHidden());
        map.put("teasingAllowed", c.getTeasingAllowed());
        map.put("learningConfidence", c.getLearningConfidence());
        map.put("sampleFactor", c.getSampleFactor());
        map.put("timeSpanFactor", c.getTimeSpanFactor());
        map.put("sceneFactor", c.getSceneFactor());
        map.put("distributionFactor", c.getDistributionFactor());
        map.put("sampleCount", c.getSampleCount());
        map.put("sceneCount", c.getSceneCount());
        map.put("styleVersion", c.getStyleVersion());
        map.put("personaVersion", c.getPersonaVersion());
        map.put("styleDescription", c.getStyleDescription());
        map.put("styleSourceHash", c.getStyleSourceHash());
        map.put("updatedAt", c.getUpdatedAt());
        return map;
    }

    @PutMapping("/api/model-params/style-config")
    @ResponseBody
    @Transactional
    public Map<String, Object> updateStyleConfig(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        LearnedStyleConfig c = styleConfigRepo.findById(1L).orElse(LearnedStyleConfig.builder().id(1L).build());
        if (body.containsKey("humorScore")) c.setHumorScore(toDouble(body.get("humorScore")));
        if (body.containsKey("sarcasmScore")) c.setSarcasmScore(toDouble(body.get("sarcasmScore")));
        if (body.containsKey("casualScore")) c.setCasualScore(toDouble(body.get("casualScore")));
        if (body.containsKey("warmthScore")) c.setWarmthScore(toDouble(body.get("warmthScore")));
        if (body.containsKey("formalScore")) c.setFormalScore(toDouble(body.get("formalScore")));
        if (body.containsKey("slangScore")) c.setSlangScore(toDouble(body.get("slangScore")));
        if (body.containsKey("avgLength")) c.setAvgLength(toInt(body.get("avgLength")));
        if (body.containsKey("lengthVariance")) c.setLengthVariance(toDouble(body.get("lengthVariance")));
        if (body.containsKey("expressionVariance")) c.setExpressionVariance(toDouble(body.get("expressionVariance")));
        if (body.containsKey("intimacyHumor")) c.setIntimacyHumor(toDouble(body.get("intimacyHumor")));
        if (body.containsKey("empathyHidden")) c.setEmpathyHidden(toDouble(body.get("empathyHidden")));
        if (body.containsKey("teasingAllowed")) c.setTeasingAllowed(toDouble(body.get("teasingAllowed")));
        if (body.containsKey("styleDescription")) c.setStyleDescription((String) body.get("styleDescription"));
        if (body.containsKey("styleVersion")) c.setStyleVersion(toInt(body.get("styleVersion")));
        if (body.containsKey("personaVersion")) c.setPersonaVersion(toInt(body.get("personaVersion")));
        styleConfigRepo.save(c);
        promptBuilder.invalidateCache();
        logService.record("模型参数", "更新人格维度", "", request);
        return Map.of("success", true);
    }

    // ========================================================================
    //  Tab 3: 表达档案 (persona_expression) — 多行 CRUD
    // ========================================================================

    @GetMapping("/api/model-params/expression")
    @ResponseBody
    public List<Map<String, Object>> listExpression() {
        return expressionRepo.findAll().stream().map(e -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", e.getId());
            map.put("phrase", e.getPhrase());
            map.put("frequency", e.getFrequency());
            map.put("confidence", e.getConfidence());
            map.put("intent", e.getIntent());
            map.put("allowedScene", e.getAllowedScene());
            map.put("triggerPattern", e.getTriggerPattern());
            map.put("fatigueScore", e.getFatigueScore());
            map.put("consecutiveUsed", e.getConsecutiveUsed());
            map.put("lastUsed", e.getLastUsed());
            map.put("updatedAt", e.getUpdatedAt());
            return map;
        }).collect(Collectors.toList());
    }

    @PostMapping("/api/model-params/expression")
    @ResponseBody
    @Transactional
    public Map<String, Object> createExpression(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String phrase = (String) body.get("phrase");
        if (phrase == null || phrase.isBlank()) return Map.of("success", false, "message", "phrase 不能为空");
        ExpressionProfile e = ExpressionProfile.builder().phrase(phrase).build();
        applyExpressionFields(e, body);
        expressionRepo.save(e);
        logService.record("模型参数", "新增表达档案", "phrase=" + phrase, request);
        return Map.of("success", true, "id", e.getId());
    }

    @PutMapping("/api/model-params/expression/{id}")
    @ResponseBody
    @Transactional
    public Map<String, Object> updateExpression(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        ExpressionProfile e = expressionRepo.findById(id).orElse(null);
        if (e == null) return Map.of("success", false, "message", "记录不存在");
        applyExpressionFields(e, body);
        expressionRepo.save(e);
        promptBuilder.invalidateCache();
        logService.record("模型参数", "编辑表达档案", "id=" + id + " phrase=" + e.getPhrase(), request);
        return Map.of("success", true);
    }

    @DeleteMapping("/api/model-params/expression/{id}")
    @ResponseBody
    @Transactional
    public Map<String, Object> deleteExpression(@PathVariable Long id, HttpServletRequest request) {
        expressionSceneRepo.deleteByExpressionId(id);
        safeDelete(expressionRepo, id);
        logService.record("模型参数", "删除表达档案", "id=" + id, request);
        return Map.of("success", true);
    }

    /** 获取某表达的场景使用计数 */
    @GetMapping("/api/model-params/expression/{id}/scenes")
    @ResponseBody
    public List<Map<String, Object>> getExpressionScenes(@PathVariable Long id) {
        return expressionSceneRepo.findByExpressionId(id).stream().map(u -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", u.getId());
            map.put("sceneType", u.getSceneType());
            map.put("usageCount", u.getUsageCount());
            return map;
        }).collect(Collectors.toList());
    }

    /** 新增/更新场景使用计数 */
    @PostMapping("/api/model-params/expression/{id}/scenes")
    @ResponseBody
    @Transactional
    public Map<String, Object> saveExpressionScene(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String sceneType = (String) body.get("sceneType");
        if (sceneType == null || sceneType.isBlank()) return Map.of("success", false, "message", "sceneType 不能为空");
        ExpressionSceneUsage usage = expressionSceneRepo.findByExpressionIdAndSceneType(id, sceneType)
                .orElse(ExpressionSceneUsage.builder().expressionId(id).sceneType(sceneType).build());
        usage.setUsageCount(toInt(body.get("usageCount")));
        expressionSceneRepo.save(usage);
        logService.record("模型参数", "更新表达场景计数", "expr=" + id + " scene=" + sceneType + " count=" + usage.getUsageCount(), request);
        return Map.of("success", true);
    }

    @DeleteMapping("/api/model-params/expression-scene/{id}")
    @ResponseBody
    @Transactional
    public Map<String, Object> deleteExpressionScene(@PathVariable Long id, HttpServletRequest request) {
        safeDelete(expressionSceneRepo, id);
        logService.record("模型参数", "删除表达场景计数", "id=" + id, request);
        return Map.of("success", true);
    }

    private void applyExpressionFields(ExpressionProfile e, Map<String, Object> body) {
        if (body.containsKey("phrase")) e.setPhrase(str(body.get("phrase")));
        if (body.containsKey("frequency")) e.setFrequency(toDouble(body.get("frequency")));
        if (body.containsKey("confidence")) e.setConfidence(toDouble(body.get("confidence")));
        if (body.containsKey("intent")) e.setIntent(str(body.get("intent")));
        if (body.containsKey("allowedScene")) e.setAllowedScene(str(body.get("allowedScene")));
        if (body.containsKey("triggerPattern")) e.setTriggerPattern(str(body.get("triggerPattern")));
        if (body.containsKey("fatigueScore")) e.setFatigueScore(toDouble(body.get("fatigueScore")));
        if (body.containsKey("consecutiveUsed")) e.setConsecutiveUsed(toInt(body.get("consecutiveUsed")));
    }

    // ========================================================================
    //  Tab 4: 关系画像 (persona_relationship_profile) — 多行 CRUD
    // ========================================================================

    @GetMapping("/api/model-params/relationship")
    @ResponseBody
    public List<Map<String, Object>> listRelationship() {
        return relationshipRepo.findAll().stream().map(r -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", r.getId());
            map.put("contactName", r.getContactName());
            map.put("relationshipType", r.getRelationshipType());
            map.put("intimacyLevel", r.getIntimacyLevel());
            map.put("humorScore", r.getHumorScore());
            map.put("sarcasmScore", r.getSarcasmScore());
            map.put("warmthScore", r.getWarmthScore());
            map.put("formalScore", r.getFormalScore());
            map.put("communicationStyle", r.getCommunicationStyle());
            map.put("sampleCount", r.getSampleCount());
            map.put("updatedAt", r.getUpdatedAt());
            return map;
        }).collect(Collectors.toList());
    }

    @PostMapping("/api/model-params/relationship")
    @ResponseBody
    @Transactional
    public Map<String, Object> createRelationship(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String name = (String) body.get("contactName");
        if (name == null || name.isBlank()) return Map.of("success", false, "message", "contactName 不能为空");
        RelationshipProfile r = RelationshipProfile.builder().contactName(name).build();
        applyRelationshipFields(r, body);
        relationshipRepo.save(r);
        logService.record("模型参数", "新增关系画像", "contact=" + name, request);
        return Map.of("success", true, "id", r.getId());
    }

    @PutMapping("/api/model-params/relationship/{id}")
    @ResponseBody
    @Transactional
    public Map<String, Object> updateRelationship(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        RelationshipProfile r = relationshipRepo.findById(id).orElse(null);
        if (r == null) return Map.of("success", false, "message", "记录不存在");
        applyRelationshipFields(r, body);
        relationshipRepo.save(r);
        logService.record("模型参数", "编辑关系画像", "id=" + id, request);
        return Map.of("success", true);
    }

    @DeleteMapping("/api/model-params/relationship/{id}")
    @ResponseBody
    @Transactional
    public Map<String, Object> deleteRelationship(@PathVariable Long id, HttpServletRequest request) {
        safeDelete(relationshipRepo, id);
        logService.record("模型参数", "删除关系画像", "id=" + id, request);
        return Map.of("success", true);
    }

    private void applyRelationshipFields(RelationshipProfile r, Map<String, Object> body) {
        if (body.containsKey("contactName")) r.setContactName(str(body.get("contactName")));
        if (body.containsKey("relationshipType")) r.setRelationshipType(str(body.get("relationshipType")));
        if (body.containsKey("intimacyLevel")) r.setIntimacyLevel(toInt(body.get("intimacyLevel")));
        if (body.containsKey("humorScore")) r.setHumorScore(toDouble(body.get("humorScore")));
        if (body.containsKey("sarcasmScore")) r.setSarcasmScore(toDouble(body.get("sarcasmScore")));
        if (body.containsKey("warmthScore")) r.setWarmthScore(toDouble(body.get("warmthScore")));
        if (body.containsKey("formalScore")) r.setFormalScore(toDouble(body.get("formalScore")));
        if (body.containsKey("communicationStyle")) r.setCommunicationStyle(str(body.get("communicationStyle")));
        if (body.containsKey("sampleCount")) r.setSampleCount(toInt(body.get("sampleCount")));
    }

    // ========================================================================
    //  Tab 5: 场景画像 (persona_scene_profile) — 多行 CRUD
    // ========================================================================

    @GetMapping("/api/model-params/scene")
    @ResponseBody
    public List<Map<String, Object>> listScene() {
        return sceneRepo.findAll().stream().map(s -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", s.getId());
            map.put("sceneType", s.getSceneType());
            map.put("humorScore", s.getHumorScore());
            map.put("sarcasmScore", s.getSarcasmScore());
            map.put("warmthScore", s.getWarmthScore());
            map.put("casualScore", s.getCasualScore());
            map.put("formalScore", s.getFormalScore());
            map.put("sampleCount", s.getSampleCount());
            map.put("updatedAt", s.getUpdatedAt());
            return map;
        }).collect(Collectors.toList());
    }

    @PostMapping("/api/model-params/scene")
    @ResponseBody
    @Transactional
    public Map<String, Object> createScene(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String type = (String) body.get("sceneType");
        if (type == null || type.isBlank()) return Map.of("success", false, "message", "sceneType 不能为空");
        SceneProfile s = SceneProfile.builder().sceneType(type).build();
        applySceneFields(s, body);
        sceneRepo.save(s);
        logService.record("模型参数", "新增场景画像", "scene=" + type, request);
        return Map.of("success", true, "id", s.getId());
    }

    @PutMapping("/api/model-params/scene/{id}")
    @ResponseBody
    @Transactional
    public Map<String, Object> updateScene(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        SceneProfile s = sceneRepo.findById(id).orElse(null);
        if (s == null) return Map.of("success", false, "message", "记录不存在");
        applySceneFields(s, body);
        sceneRepo.save(s);
        logService.record("模型参数", "编辑场景画像", "id=" + id, request);
        return Map.of("success", true);
    }

    @DeleteMapping("/api/model-params/scene/{id}")
    @ResponseBody
    @Transactional
    public Map<String, Object> deleteScene(@PathVariable Long id, HttpServletRequest request) {
        safeDelete(sceneRepo, id);
        logService.record("模型参数", "删除场景画像", "id=" + id, request);
        return Map.of("success", true);
    }

    private void applySceneFields(SceneProfile s, Map<String, Object> body) {
        if (body.containsKey("sceneType")) s.setSceneType(str(body.get("sceneType")));
        if (body.containsKey("humorScore")) s.setHumorScore(toDouble(body.get("humorScore")));
        if (body.containsKey("sarcasmScore")) s.setSarcasmScore(toDouble(body.get("sarcasmScore")));
        if (body.containsKey("warmthScore")) s.setWarmthScore(toDouble(body.get("warmthScore")));
        if (body.containsKey("casualScore")) s.setCasualScore(toDouble(body.get("casualScore")));
        if (body.containsKey("formalScore")) s.setFormalScore(toDouble(body.get("formalScore")));
        if (body.containsKey("sampleCount")) s.setSampleCount(toInt(body.get("sampleCount")));
    }

    // ========================================================================
    //  Tab 6: 当前状态 (current_state_profile + persona_stability) — 单行
    // ========================================================================

    @GetMapping("/api/model-params/current-state")
    @ResponseBody
    public Map<String, Object> getCurrentState() {
        CurrentStateProfile cs = currentStateRepo.findById(1L).orElse(null);
        PersonaStability ps = stabilityRepo.findById(1L).orElse(null);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stateExists", cs != null);
        if (cs != null) {
            map.put("energy", cs.getEnergy());
            map.put("stress", cs.getStress());
            map.put("socialMode", cs.getSocialMode());
            map.put("messageCount7d", cs.getMessageCount7d());
            map.put("stateVersion", cs.getStateVersion());
            map.put("stateUpdatedAt", cs.getUpdatedAt());
        }
        map.put("stabilityExists", ps != null);
        if (ps != null) {
            map.put("humorStability", ps.getHumorStability());
            map.put("sarcasmStability", ps.getSarcasmStability());
            map.put("warmthStability", ps.getWarmthStability());
            map.put("stabilityUpdatedAt", ps.getUpdatedAt());
        }
        return map;
    }

    @PutMapping("/api/model-params/current-state")
    @ResponseBody
    @Transactional
    public Map<String, Object> updateCurrentState(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        CurrentStateProfile cs = currentStateRepo.findById(1L).orElse(CurrentStateProfile.builder().id(1L).build());
        if (body.containsKey("energy")) cs.setEnergy(toDouble(body.get("energy")));
        if (body.containsKey("stress")) cs.setStress(toDouble(body.get("stress")));
        if (body.containsKey("socialMode")) cs.setSocialMode(toDouble(body.get("socialMode")));
        if (body.containsKey("messageCount7d")) cs.setMessageCount7d(toInt(body.get("messageCount7d")));
        if (body.containsKey("stateVersion")) cs.setStateVersion(toInt(body.get("stateVersion")));
        currentStateRepo.save(cs);

        PersonaStability ps = stabilityRepo.findById(1L).orElse(PersonaStability.builder().id(1L).build());
        if (body.containsKey("humorStability")) ps.setHumorStability(toDouble(body.get("humorStability")));
        if (body.containsKey("sarcasmStability")) ps.setSarcasmStability(toDouble(body.get("sarcasmStability")));
        if (body.containsKey("warmthStability")) ps.setWarmthStability(toDouble(body.get("warmthStability")));
        stabilityRepo.save(ps);

        promptBuilder.invalidateCache();
        logService.record("模型参数", "更新当前状态", "", request);
        return Map.of("success", true);
    }

    // ========================================================================
    //  Tab 7: 学习快照 (persona_style_snapshot) — 只读 + 删除
    // ========================================================================

    @GetMapping("/api/model-params/snapshot")
    @ResponseBody
    public List<Map<String, Object>> listSnapshot() {
        return snapshotRepo.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(s -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", s.getId());
                    map.put("styleVersion", s.getStyleVersion());
                    map.put("personaVersion", s.getPersonaVersion());
                    map.put("humorScore", s.getHumorScore());
                    map.put("sarcasmScore", s.getSarcasmScore());
                    map.put("casualScore", s.getCasualScore());
                    map.put("warmthScore", s.getWarmthScore());
                    map.put("formalScore", s.getFormalScore());
                    map.put("styleDescription", s.getStyleDescription());
                    map.put("learningConfidence", s.getLearningConfidence());
                    map.put("sampleCount", s.getSampleCount());
                    map.put("sceneCount", s.getSceneCount());
                    map.put("trigger", s.getTrigger());
                    map.put("createdAt", s.getCreatedAt());
                    return map;
                }).collect(Collectors.toList());
    }

    @DeleteMapping("/api/model-params/snapshot/{id}")
    @ResponseBody
    @Transactional
    public Map<String, Object> deleteSnapshot(@PathVariable Long id, HttpServletRequest request) {
        safeDelete(snapshotRepo, id);
        logService.record("模型参数", "删除学习快照", "id=" + id, request);
        return Map.of("success", true);
    }

    // ========================================================================
    //  工具方法
    // ========================================================================

    /** 安全取字符串值，null 返回 null */
    private String str(Object v) {
        return v != null ? v.toString() : null;
    }

    private double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object v) {
        if (v instanceof List) return ((List<Object>) v).stream().map(Object::toString).collect(Collectors.toList());
        return new ArrayList<>();
    }

    /** 安全删除：先检查是否存在再删除 */
    private void safeDelete(org.springframework.data.jpa.repository.JpaRepository<?, Long> repo, Long id) {
        if (repo.existsById(id)) repo.deleteById(id);
    }
}
