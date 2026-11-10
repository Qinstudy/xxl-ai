/*
 * 公众号 Markdown 终稿排版防崩扫描工具
 *
 * @author maque 2026-11-07
 */
const fs = require('fs');
const path = require('path');

/* 加载仓库内已固定的 marked 解析器做真实 CommonMark AST 校验 */
const markedPath = path.resolve(__dirname, '../../../../xxl-ai-ui/node_modules/marked');
const marked = require(markedPath);

const targetFile = process.argv[2];
if (!targetFile) {
  console.error('用法: node .agents/skills/agent-platform-series/scripts/check_article.js <markdown文件路径>');
  process.exit(1);
}

const content = fs.readFileSync(targetFile, 'utf8');
const lines = content.split(/\r?\n/);

let inCodeBlock = false;
let errorCount = 0;
let bangCount = 0;

lines.forEach((line, idx) => {
  const trimmed = line.trim();
  if (trimmed.startsWith('```')) {
    inCodeBlock = !inCodeBlock;
    return;
  }
  if (inCodeBlock) {
    return;
  }

  /* 1. CommonMark AST 校验：转为 HTML 并剥离 <code> 后，检查是否残留未解析的 ** */
  const html = marked.parse(line);
  const withoutInlineCode = html.replace(/<code>[\s\S]*?<\/code>/g, '');
  if (withoutInlineCode.includes('**')) {
    console.log(`❌ [第 ${idx + 1} 行] 加粗星号解析失败（CommonMark 标点边界冲突）:`);
    console.log(`   原文: ${line}`);
    console.log(`   渲染: ${html.trim()}`);
    errorCount++;
  }

  /* 2. 统计正文中文感叹号数量（辅助区除外） */
  if (!trimmed.startsWith('* ') && !trimmed.startsWith('### 📋')) {
    const matches = line.replace(/`[^`]+`/g, '').match(/！/g);
    if (matches) {
      bangCount += matches.length;
    }
  }

  /* 3. 检查是否写死了易漂移的具体行号 */
  if (/第\s*\d+\s*行/.test(line)) {
    console.log(`⚠️ [第 ${idx + 1} 行] 检测到写死行号表述（建议改为类名+方法/字段名）: ${line}`);
  }
});

console.log(`\n📊 扫描汇总:`);
console.log(`   - 未解析加粗错位 (**): ${errorCount} 处 ${errorCount === 0 ? '✅' : '❌ 必须清零！'}`);
console.log(`   - 全文中文感叹号 (！): ${bangCount} 个 ${bangCount <= 8 ? '✅ (<=8)' : '⚠️ 建议精简至 8 个以内'}`);

if (errorCount > 0) {
  process.exit(1);
}
