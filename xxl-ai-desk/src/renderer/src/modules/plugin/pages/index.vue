<script setup lang="ts">
/*
 * 插件模块正文：按路由挂载 MCP 插件中心或 SKILL 技能中心。
 * @author xuxueli 2026-10-18
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import McpCenter from '../components/McpCenter.vue'
import SkillCenter from '../skill/pages/index.vue'

const route = useRoute()

/* 是否 SKILL 技能路由（其余为 MCP 插件中心） */
const isSkill = computed(() => route.path.endsWith('/skill'))
</script>

<template>
  <SkillCenter v-if="isSkill" />
  <McpCenter v-else />
</template>
