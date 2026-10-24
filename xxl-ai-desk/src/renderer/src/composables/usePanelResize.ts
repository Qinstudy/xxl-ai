/*
* 面板尺寸拖拽：统一左侧栏（手柄在右缘）、右侧栏（手柄在左缘）与底部终端（手柄在上缘）的拖拽调整交互
* @author xuxueli 2026-10-18
*/

/*
 * 拖拽边缘（按面板所处位置命名，手柄位于其朝内一缘）：
 *   left = 左侧栏（手柄在右缘，向右拖变大）；right = 右侧栏（手柄在左缘，向左拖变大）；
 *   top = 顶部面板（手柄在下缘，向下拖变大）；bottom = 底部面板（手柄在上缘，向上拖变大）
 */
export type PanelResizeEdge = 'left' | 'right' | 'top' | 'bottom'

/* 入参：读写尺寸（宽度/高度由调用方/Pinia 持有）+ 手柄所在边缘 */
export interface PanelResizeOptions {
  getSize: () => number
  setSize: (size: number) => void
  edge: PanelResizeEdge
}

/* 返回拖拽启动函数，供手柄 mousedown 绑定 */
export function usePanelResize(options: PanelResizeOptions): {
  startResize: (event: MouseEvent) => void
} {
  /* 开始拖拽：按边缘方向换算位移，实时写回尺寸；拖拽期间禁用过渡与文本选中 */
  function startResize(event: MouseEvent): void {
    event.preventDefault()
    /* 纵向手柄（top/bottom）按 Y 轴位移换算，横向手柄按 X 轴 */
    const vertical = options.edge === 'top' || options.edge === 'bottom'
    const startPos = vertical ? event.clientY : event.clientX
    const startSize = options.getSize()
    /* 按面板位置换算方向：左侧栏/顶部面板随鼠标正向变大，右侧栏/底部面板反向变大 */
    const direction = options.edge === 'left' || options.edge === 'top' ? 1 : -1
    const onMove = (e: MouseEvent): void => {
      const delta = (vertical ? e.clientY : e.clientX) - startPos
      options.setSize(startSize + direction * delta)
    }
    const onUp = (): void => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      document.body.classList.remove('resizing', 'resizing-vertical')
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
    document.body.classList.add('resizing')
    /* 纵向拖拽附加类名，切换为行高光标 */
    if (vertical) {
      document.body.classList.add('resizing-vertical')
    }
  }

  return { startResize }
}
