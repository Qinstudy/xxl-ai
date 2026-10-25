<script setup lang="ts">
/*
 * 通用面板拖拽手柄：统一左右侧栏 / 底部终端的边缘拖拽、命中区与双击复位交互。
 * @author xuxueli 2026-10-25
 */
import { computed } from 'vue'
import { usePanelResize, type PanelResizeEdge } from '@renderer/composables/usePanelResize'

const props = defineProps<{
  /* 手柄所在边缘（按面板位置命名，与 usePanelResize 一致） */
  edge: PanelResizeEdge
  /* 悬浮提示文案（拖拽调整尺寸） */
  title?: string
}>()

/* 双向绑定尺寸：由调用方提供带夹取的读写（如 Pinia setter） */
const size = defineModel<number>('size', { required: true })
const emit = defineEmits<{ reset: [] }>()

const { startResize } = usePanelResize({
  getSize: () => size.value,
  setSize: (value) => {
    size.value = value
  },
  edge: props.edge
})

/* 手柄方向：纵向边缘（上下）用于底部面板，横向边缘（左右）用于侧栏 */
const vertical = computed(() => props.edge === 'top' || props.edge === 'bottom')
</script>

<template>
  <div
    class="panel-resizer"
    :class="[`edge-${edge}`, { vertical }]"
    :title="title"
    @mousedown="startResize"
    @dblclick="emit('reset')"
  ></div>
</template>

<style scoped lang="scss">
/* 视觉条保持 5px，实际命中区由 ::after 扩大，靠近分割线即可拖动 */
.panel-resizer {
  position: absolute;
  z-index: 10;
  -webkit-app-region: no-drag;
}

.panel-resizer.edge-left {
  top: 0;
  right: 0;
  width: 5px;
  height: 100%;
  cursor: col-resize;
}

.panel-resizer.edge-right {
  top: 0;
  left: 0;
  width: 5px;
  height: 100%;
  cursor: col-resize;
}

.panel-resizer.edge-top {
  left: 0;
  bottom: 0;
  width: 100%;
  height: 5px;
  cursor: row-resize;
}

.panel-resizer.edge-bottom {
  top: 0;
  left: 0;
  width: 100%;
  height: 5px;
  cursor: row-resize;
}

/* 透明命中区：视觉条保持 5px，可抓取范围更宽 */
.panel-resizer.edge-left::after,
.panel-resizer.edge-right::after {
  content: '';
  position: absolute;
  top: 0;
  left: -6px;
  width: 16px;
  height: 100%;
}

.panel-resizer.edge-top::after,
.panel-resizer.edge-bottom::after {
  content: '';
  position: absolute;
  top: -6px;
  left: 0;
  width: 100%;
  height: 16px;
}

.panel-resizer:hover,
:global(body.resizing) .panel-resizer {
  background: var(--desk-primary-soft);
}
</style>
