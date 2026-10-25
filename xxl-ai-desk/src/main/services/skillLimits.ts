/*
 * 在线安装（GitHub）技能的资源阈值：只保留会产生资源消耗风险的限制。
 *   - 本地目录 / Zip 导入不做体积与数量校验，不在此列；
 *   - 路径穿越 / 符号链接 / Zip Slip 等安全栅栏由各服务独立实现，也不在此列。
 * @author xuxueli 2026-10-25
 */

/* 单技能总体积上限（字节） */
export const SKILL_MAX_BYTES = 100 * 1024 * 1024

/* 单个文件体积上限（字节，在线下载需整块读入内存） */
export const SKILL_MAX_FILE_BYTES = 20 * 1024 * 1024
