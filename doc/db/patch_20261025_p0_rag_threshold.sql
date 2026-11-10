SET NAMES utf8mb4;

-- -------------------------------------------------------------
-- 阶段一 P0 缺陷修复补丁：[RAG-CORE-002] 知识库检索相似度阈值截断
-- -------------------------------------------------------------

ALTER TABLE `xxl_ai_knowledge_base` 
ADD COLUMN `similarity_threshold` DECIMAL(4, 2) NOT NULL DEFAULT 0.40 COMMENT '相似度阈值 (0.00-1.00，默认 0.40)' 
AFTER `top_k`;
