# 通用参赛作品表重构 — 数据库迁移手册

> **数据库**: `peacehaven_db_pre` (阿里云 RDS MySQL)
> **Hibernate ddl-auto**: `update`（自动创建新表、自动新增列，不会删除旧表）
> **核心思路**: 启动应用让 Hibernate 自动建表 → INSERT SELECT 迁移数据 → 删除旧表

---

## 迁移概览

| 序号 | 操作 | 说明 |
|------|------|------|
| 0 | 准备 & 备份 | 备份旧表数据 |
| 1 | 重启应用 | Hibernate 自动创建 4 张新表 + activity 新增列 |
| 2 | 迁移数据 | INSERT INTO ... SELECT 将旧表数据写入新表 |
| 3 | 同步自增ID | 确保新表 auto_increment 接续旧表最大值 |
| 4 | 数据回填 | activity 表标记 building-master-1 启用作品提交 |
| 5 | 删除旧表 | DROP 4 张旧表 + 备份表 + 无用的 building_contest_judge |
| 6 | 验证 | 数据一致性校验 + 功能验证 |

### 涉及的表（4 张迁移 + 1 张废弃）

| 旧表 | 新表 | 说明 |
|------|------|------|
| `building_contest_work` | `contest_work` | 参赛作品主表 |
| `building_contest_vote` | `contest_vote` | 投票记录 |
| `building_contest_abstract_vote` | `contest_abstract_vote` | 抽象票记录 |
| `building_contest_judge_score` | `contest_judge_score` | 裁判评分 |
| `building_contest_judge` | — (废弃) | 裁判映射已有 `activity_judge`，此表无用，直接删除 |

---

## 第 0 步：准备 & 备份

```sql
-- 备份 4 张需要迁移的旧表
CREATE TABLE building_contest_work_bak          AS SELECT * FROM building_contest_work;
CREATE TABLE building_contest_vote_bak          AS SELECT * FROM building_contest_vote;
CREATE TABLE building_contest_abstract_vote_bak AS SELECT * FROM building_contest_abstract_vote;
CREATE TABLE building_contest_judge_score_bak   AS SELECT * FROM building_contest_judge_score;
```

---

## 第 1 步：重启应用（自动建表 + 新增列）

部署新代码并启动 Spring Boot 应用。Hibernate `ddl-auto: update` 会自动完成：

- 创建 `contest_work` 表
- 创建 `contest_vote` 表
- 创建 `contest_abstract_vote` 表
- 创建 `contest_judge_score` 表
- `activity` 表新增 `has_work_submission` 列（默认 0）

> 新表此时为空表，activity 新列默认值 0（所有活动均未启用作品提交）。

### 1.1 确认新表已创建
```sql
SELECT TABLE_NAME FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'peacehaven_db_pre'
  AND TABLE_NAME IN ('contest_work','contest_vote','contest_abstract_vote','contest_judge_score')
ORDER BY TABLE_NAME;
-- 期望返回 4 行
```

---

## 第 2 步：迁移数据

> **必须按以下顺序执行**（先迁移主表 contest_work，再迁移引用它的子表）。

### 2.1 迁移作品主表
> 注意：新表已移除 4 个冗余列（vote_count, abstract_vote_count, judge_score, final_score），这些数据改为实时从投票表/评分表聚合查询。
```sql
INSERT INTO contest_work (id, activity_id, user_id, title, description, image_url,
                          status, created_at, updated_at)
SELECT id, activity_id, user_id, title, description, image_url,
       status, created_at, updated_at
FROM building_contest_work;
```

### 2.2 迁移投票记录
```sql
INSERT INTO contest_vote (id, work_id, user_id, created_at)
SELECT id, work_id, user_id, created_at
FROM building_contest_vote;
```

### 2.3 迁移抽象票记录
```sql
INSERT INTO contest_abstract_vote (id, activity_id, work_id, user_id, created_at, updated_at)
SELECT id, activity_id, work_id, user_id, created_at, updated_at
FROM building_contest_abstract_vote;
```

### 2.4 迁移裁判评分
```sql
INSERT INTO contest_judge_score (id, work_id, judge_id, score, created_at)
SELECT id, work_id, judge_id, score, created_at
FROM building_contest_judge_score;
```

---

## 第 3 步：同步自增 ID

确保新表的 auto_increment 接续旧表最大 ID，避免后续插入时主键冲突：

```sql
-- contest_work
SELECT @max_id := IFNULL(MAX(id), 0) FROM contest_work;
SET @sql = CONCAT('ALTER TABLE contest_work AUTO_INCREMENT = ', @max_id + 1);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- contest_vote
SELECT @max_id := IFNULL(MAX(id), 0) FROM contest_vote;
SET @sql = CONCAT('ALTER TABLE contest_vote AUTO_INCREMENT = ', @max_id + 1);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- contest_abstract_vote
SELECT @max_id := IFNULL(MAX(id), 0) FROM contest_abstract_vote;
SET @sql = CONCAT('ALTER TABLE contest_abstract_vote AUTO_INCREMENT = ', @max_id + 1);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- contest_judge_score
SELECT @max_id := IFNULL(MAX(id), 0) FROM contest_judge_score;
SET @sql = CONCAT('ALTER TABLE contest_judge_score AUTO_INCREMENT = ', @max_id + 1);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
```

---

## 第 4 步：数据回填

```sql
UPDATE activity SET has_work_submission = 1 WHERE slug = 'building-master-1';
```

---

## 第 5 步：删除旧表 & 备份表

> 确认第 6 步验证通过后再执行此步骤。

```sql
-- 删除 4 张已迁移的旧表
DROP TABLE IF EXISTS building_contest_work;
DROP TABLE IF EXISTS building_contest_vote;
DROP TABLE IF EXISTS building_contest_abstract_vote;
DROP TABLE IF EXISTS building_contest_judge_score;

-- 删除废弃的裁判映射表（功能已由 activity_judge 替代）
DROP TABLE IF EXISTS building_contest_judge;

-- 确认无误后删除备份
DROP TABLE IF EXISTS building_contest_work_bak;
DROP TABLE IF EXISTS building_contest_vote_bak;
DROP TABLE IF EXISTS building_contest_abstract_vote_bak;
DROP TABLE IF EXISTS building_contest_judge_score_bak;
```

---

## 第 6 步：验证

### 6.1 数据一致性校验
```sql
-- 新旧表行数对比（删旧表前执行）
SELECT 'work' AS tbl,
       (SELECT COUNT(*) FROM building_contest_work) AS old_cnt,
       (SELECT COUNT(*) FROM contest_work) AS new_cnt
UNION ALL SELECT 'vote',
       (SELECT COUNT(*) FROM building_contest_vote),
       (SELECT COUNT(*) FROM contest_vote)
UNION ALL SELECT 'abstract_vote',
       (SELECT COUNT(*) FROM building_contest_abstract_vote),
       (SELECT COUNT(*) FROM contest_abstract_vote)
UNION ALL SELECT 'judge_score',
       (SELECT COUNT(*) FROM building_contest_judge_score),
       (SELECT COUNT(*) FROM contest_judge_score);
-- 期望: 每行 old_cnt = new_cnt

-- activity 新列验证
SELECT id, slug, title, has_work_submission FROM activity;
```

### 6.2 功能验证清单
- [ ] `/admin/contest-works` 能看到建筑大赛活动卡片
- [ ] 点击活动进入作品审核详情页，能加载作品列表
- [ ] 前台 `building-master-1` 页面投稿/投票/删作品功能正常
- [ ] 裁判面板 `/judge/building-master-1` 能正常加载作品

---

## 回滚方案（第 5 步之前可回滚）

```sql
-- 清空新表数据
TRUNCATE TABLE contest_vote;
TRUNCATE TABLE contest_abstract_vote;
TRUNCATE TABLE contest_judge_score;
TRUNCATE TABLE contest_work;

-- 从备份恢复旧表数据（如果旧表已被删）
INSERT INTO building_contest_work          SELECT * FROM building_contest_work_bak;
INSERT INTO building_contest_vote          SELECT * FROM building_contest_vote_bak;
INSERT INTO building_contest_abstract_vote SELECT * FROM building_contest_abstract_vote_bak;
INSERT INTO building_contest_judge_score   SELECT * FROM building_contest_judge_score_bak;

-- 回滚 activity 列
ALTER TABLE activity DROP COLUMN has_work_submission;

-- 回滚代码到旧版本后重启应用即可
```

---

## 一键执行脚本（合并版）

> 第 1 步（重启应用）完成后，以下 SQL 可一次性执行：

```sql
-- ========== 0. 备份 ==========
CREATE TABLE building_contest_work_bak          AS SELECT * FROM building_contest_work;
CREATE TABLE building_contest_vote_bak          AS SELECT * FROM building_contest_vote;
CREATE TABLE building_contest_abstract_vote_bak AS SELECT * FROM building_contest_abstract_vote;
CREATE TABLE building_contest_judge_score_bak   AS SELECT * FROM building_contest_judge_score;

-- ========== 2. 迁移数据（先主表后子表）==========
-- 注意：contest_work 已移除 4 个冗余列，投票/评分数据从子表实时聚合
INSERT INTO contest_work (id, activity_id, user_id, title, description, image_url,
                          status, created_at, updated_at)
SELECT id, activity_id, user_id, title, description, image_url,
       status, created_at, updated_at
FROM building_contest_work;

INSERT INTO contest_vote (id, work_id, user_id, created_at)
SELECT id, work_id, user_id, created_at FROM building_contest_vote;

INSERT INTO contest_abstract_vote (id, activity_id, work_id, user_id, created_at, updated_at)
SELECT id, activity_id, work_id, user_id, created_at, updated_at FROM building_contest_abstract_vote;

INSERT INTO contest_judge_score (id, work_id, judge_id, score, created_at)
SELECT id, work_id, judge_id, score, created_at FROM building_contest_judge_score;

-- ========== 3. 同步自增ID ==========
SELECT @max_id := IFNULL(MAX(id), 0) FROM contest_work;
SET @sql = CONCAT('ALTER TABLE contest_work AUTO_INCREMENT = ', @max_id + 1);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT @max_id := IFNULL(MAX(id), 0) FROM contest_vote;
SET @sql = CONCAT('ALTER TABLE contest_vote AUTO_INCREMENT = ', @max_id + 1);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT @max_id := IFNULL(MAX(id), 0) FROM contest_abstract_vote;
SET @sql = CONCAT('ALTER TABLE contest_abstract_vote AUTO_INCREMENT = ', @max_id + 1);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT @max_id := IFNULL(MAX(id), 0) FROM contest_judge_score;
SET @sql = CONCAT('ALTER TABLE contest_judge_score AUTO_INCREMENT = ', @max_id + 1);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ========== 4. 数据回填 ==========
UPDATE activity SET has_work_submission = 1 WHERE slug = 'building-master-1';

-- ========== 6. 验证（手动检查后再执行第5步删表）==========
```
