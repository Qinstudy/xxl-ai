/*
* 桌面端路由：hash 模式，含对话页、设置页与兜底重定向
*/
import { createRouter, createWebHashHistory } from 'vue-router'
import type { Component } from 'vue'
import { NAV_SECTIONS } from '@renderer/config/nav'

/* 插件子页组件映射：路由项由导航配置统一生成，新增插件页只改 nav + 此映射 */
const PLUGIN_PAGES: Record<string, () => Promise<{ default: Component }>> = {
  mcp: () => import('@renderer/modules/plugin/mcp/pages/index.vue'),
  skill: () => import('@renderer/modules/plugin/skill/pages/index.vue'),
  builtin: () => import('@renderer/modules/plugin/builtin/pages/index.vue')
}

/* 桌面端使用 hash 路由，兼容 file:// 加载 */
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/',
      name: 'chat',
      component: () => import('@renderer/modules/chat/pages/index.vue')
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@renderer/modules/settings/pages/index.vue')
    },
    {
      path: '/plugin',
      component: () => import('@renderer/modules/plugin/pages/index.vue'),
      redirect: NAV_SECTIONS.plugin.entryPath,
      children: NAV_SECTIONS.plugin.items
        .filter((item) => item.path && PLUGIN_PAGES[item.key])
        .map((item) => ({
          path: item.key,
          name: `plugin-${item.key}`,
          component: PLUGIN_PAGES[item.key]
        }))
    },
    {
      /* 未匹配路由兜底到对话首页 */
      path: '/:pathMatch(.*)*',
      redirect: '/'
    }
  ]
})

export default router
