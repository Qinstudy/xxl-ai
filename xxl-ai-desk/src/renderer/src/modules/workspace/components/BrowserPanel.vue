<script setup lang="ts">
/*
 * 右侧「浏览器」面板：基于 Electron <webview> 的内嵌浏览器，支持多标签页。
 * 标签页生命周期与导航状态由 useBrowserTabs 统一管理，组件只负责视图与交互。
 * @author xuxueli 2026-10-25
 */
import { t } from '@renderer/i18n'
import { useBrowserTabs } from '../composables/useBrowserTabs'

const {
  tabs,
  activeId,
  activeTab,
  addressInput,
  tabLabel,
  setContainer,
  addTab,
  selectTab,
  closeTab,
  back,
  forward,
  reloadOrStop,
  go,
  openExternal,
  onMore,
  onAddressFocus,
  onAddressBlur
} = useBrowserTabs()
</script>

<template>
  <div class="browser-panel">
    <!-- 标签页栏 -->
    <div class="tab-bar">
      <div
        v-for="tab in tabs"
        :key="tab.id"
        class="tab"
        :class="{ active: tab.id === activeId }"
        :title="tabLabel(tab)"
        @click="selectTab(tab.id)"
      >
        <span class="tab-title">{{ tabLabel(tab) }}</span>
        <button class="tab-close" :title="t('panel.closeTab')" @click.stop="closeTab(tab.id)">
          <el-icon><Close /></el-icon>
        </button>
      </div>
      <button class="tab-add" :title="t('panel.newTab')" @click="addTab()">
        <el-icon><Plus /></el-icon>
      </button>
    </div>

    <!-- 地址栏工具条 -->
    <div class="browser-toolbar">
      <button class="nav-btn" :disabled="!activeTab?.canBack" :title="t('panel.goBack')" @click="back">
        <el-icon><ArrowLeft /></el-icon>
      </button>
      <button class="nav-btn" :disabled="!activeTab?.canForward" :title="t('panel.goForward')" @click="forward">
        <el-icon><ArrowRight /></el-icon>
      </button>
      <button
        class="nav-btn"
        :title="activeTab?.loading ? t('panel.stop') : t('panel.reload')"
        @click="reloadOrStop"
      >
        <el-icon v-if="activeTab?.loading"><Close /></el-icon>
        <el-icon v-else><Refresh /></el-icon>
      </button>

      <div class="address">
        <input
          v-model="addressInput"
          class="address-input"
          :placeholder="t('panel.browserPlaceholder')"
          spellcheck="false"
          @keydown.enter="go"
          @focus="onAddressFocus"
          @blur="onAddressBlur"
        />
        <button
          v-if="activeTab?.hasPage"
          class="address-external"
          :title="t('panel.openExternal')"
          @click="openExternal"
        >
          <el-icon><TopRight /></el-icon>
        </button>
      </div>

      <!-- 更多操作：在系统浏览器打开 / 刷新 -->
      <el-dropdown trigger="click" @command="onMore">
        <button class="nav-btn"><el-icon><MoreFilled /></el-icon></button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="external" :disabled="!activeTab?.hasPage">
              <el-icon><TopRight /></el-icon>{{ t('panel.openExternal') }}
            </el-dropdown-item>
            <el-dropdown-item command="reload">
              <el-icon><Refresh /></el-icon>{{ t('panel.reload') }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <!-- 内容区：每个标签页一个 webview 宿主，仅当前页可见 -->
    <div class="browser-body">
      <div
        v-for="tab in tabs"
        :key="tab.id"
        class="webview-slot"
        :class="{ active: tab.id === activeId }"
        :ref="(el) => setContainer(tab.id, el)"
      >
        <!-- 加载进度条：覆盖在内容顶部，不引起 webview 尺寸抖动 -->
        <div v-if="tab.hasPage && tab.loading" class="load-bar"><span></span></div>
        <div v-if="!tab.hasPage" class="browser-empty">
          <svg class="globe" viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="12" r="9" />
            <path d="M3 12h18" />
            <path d="M12 3c2.5 2.6 4 5.6 4 9s-1.5 6.4-4 9c-2.5-2.6-4-5.6-4-9s1.5-6.4 4-9z" />
          </svg>
          <div class="empty-title">{{ t('panel.browserStart') }}</div>
          <div class="empty-tip">{{ t('panel.browserStartTip') }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.browser-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

/* 标签页栏 */
.tab-bar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 8px 0 10px;
  overflow-x: auto;
}

.tab {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  max-width: 168px;
  height: 28px;
  padding: 0 4px 0 10px;
  border-radius: 8px 8px 0 0;
  color: var(--desk-text-secondary);
  font-size: 12px;
  cursor: pointer;
  user-select: none;
  transition:
    background 0.15s ease,
    color 0.15s ease;
}

.tab:hover {
  background: var(--desk-primary-soft);
  color: var(--desk-text);
}

.tab.active {
  background: var(--desk-bg-elevated);
  color: var(--desk-text);
}

.tab-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tab-close,
.tab-add {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--desk-text-tertiary);
  font-size: 12px;
  cursor: pointer;
}

.tab-close:hover,
.tab-add:hover {
  background: var(--desk-primary-soft);
  color: var(--desk-text);
}

.tab-add {
  width: 26px;
  height: 26px;
  font-size: 14px;
  border-radius: 8px;
}

.browser-toolbar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px 8px 16px;
  border-bottom: 1px solid var(--desk-border);
}

.nav-btn {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--desk-text-secondary);
  font-size: 15px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition:
    background 0.15s ease,
    color 0.15s ease;
}

.nav-btn:hover:not(:disabled) {
  background: var(--desk-primary-soft);
  color: var(--desk-text);
}

.nav-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

/* 地址栏：圆角胶囊，右侧内嵌「外部打开」按钮 */
.address {
  position: relative;
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  height: 30px;
  margin: 0 4px;
  padding: 0 4px 0 12px;
  border-radius: 15px;
  background: var(--desk-bg-elevated);
  border: 1px solid transparent;
}

.address:focus-within {
  border-color: var(--desk-border);
  background: var(--desk-bg);
}

.address-input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  color: var(--desk-text);
  font-size: 13px;
}

.address-input::placeholder {
  color: var(--desk-text-tertiary);
}

.address-external {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--desk-text-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.address-external:hover {
  background: var(--desk-primary-soft);
  color: var(--desk-text);
}

.browser-body {
  position: relative;
  flex: 1;
  min-height: 0;
  background: var(--desk-bg);
}

/* 标签页内容槽：仅当前页展示，切换保留各页状态 */
.webview-slot {
  position: absolute;
  inset: 0;
  display: none;
}

.webview-slot.active {
  display: block;
}

/* 加载进度条：顶部滑动指示，仅覆盖不占位 */
.load-bar {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  overflow: hidden;
  z-index: 3;
}

.load-bar span {
  position: absolute;
  top: 0;
  left: 0;
  width: 40%;
  height: 100%;
  border-radius: 2px;
  background: var(--desk-primary);
  animation: load-slide 1.1s ease-in-out infinite;
}

@keyframes load-slide {
  0% {
    transform: translateX(-100%);
  }

  100% {
    transform: translateX(350%);
  }
}

/* 空态：居中提示，覆盖在 webview 之上 */
.browser-empty {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  background: var(--desk-bg);
  color: var(--desk-text-tertiary);
}

.globe {
  width: 48px;
  height: 48px;
  fill: none;
  stroke: var(--desk-text-tertiary);
  stroke-width: 1.4;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.empty-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--desk-text);
}

.empty-tip {
  font-size: 13px;
  color: var(--desk-text-tertiary);
}
</style>
