<script setup lang="ts">
/*
 * 主内容区：路由页面出口 + 壳级终端宿主
 * @author xuxueli 2026-10-31
 */
import { TerminalHost } from '@renderer/modules/workspace'
</script>

<template>
  <section class="app-main">
    <!-- 页面容器：占满终端以外的剩余高度，供各页面 height:100% 使用 -->
    <div class="app-main-view">
      <RouterView />
    </div>

    <!-- 底部终端：首次打开后常驻挂载（域逻辑在 workspace 内） -->
    <TerminalHost />
  </section>
</template>

<style scoped lang="scss">
/* 主内容区：正文 + 底部终端 */
.app-main {
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--desk-bg);
}

/* 页面容器：占满终端以外的剩余高度 */
.app-main-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
