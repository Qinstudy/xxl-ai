/*
 * 布局尺寸：侧栏与工具面板的默认值与边界（JS 侧唯一来源）
 * @author xuxueli 2026-10-31
 */

/* 布局尺寸常量：与 styles/tokens.scss 中的同名 token 保持一致 */
export const LAYOUT = {
  /* 左侧常驻图标栏宽度 */
  railWidth: 38,
  /* 左侧子菜单栏宽度 */
  sidebar: { default: 240, min: 180, max: 520 },
  /* 右侧工具面板宽度 / 正文最小可用宽度 */
  toolPanel: { defaultWidth: 520, minWidth: 240, minChatWidth: 360 }
} as const
