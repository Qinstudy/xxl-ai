<script setup lang="ts">
/*
 * 插件模块正文：按路由挂载 MCP 插件中心、SKILL 技能中心或内置工具页。
 * @author xuxueli 2026-10-25
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import McpCenter from '../components/McpCenter.vue'
import SkillCenter from '../skill/pages/index.vue'
import BuiltinToolCenter from '../builtin/pages/index.vue'

const route = useRoute()

/* 当前插件子页面（mcp / skill / builtin） */
const activeTool = computed(() => String(route.params.tool ?? 'mcp'))
</script>

<template>
  <SkillCenter v-if="activeTool === 'skill'" />
  <BuiltinToolCenter v-else-if="activeTool === 'builtin'" />
  <McpCenter v-else />
</template>
