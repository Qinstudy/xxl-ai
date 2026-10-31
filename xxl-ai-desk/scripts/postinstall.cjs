/*
 * 安装后置处理：安装 Electron 运行时、按 Electron 版本重建原生依赖，
 * 并确保 node-pty 的 spawn-helper 具备可执行权限（缺失会导致终端报 posix_spawnp failed）。
 * 权限修复放在最后且不受前面步骤失败影响；任一步失败时整体返回非 0，不掩盖异常。
 */

const { spawnSync } = require('child_process')
const fs = require('fs')
const path = require('path')

/* 安装步骤：安装 Electron 运行时 / 重建原生依赖（better-sqlite3、node-pty） */
const STEPS = [
  { command: 'install-electron', args: [] },
  { command: 'electron-builder', args: ['install-app-deps'] }
]

/* 递归为 node-pty 目录下的 spawn-helper 补可执行权限 */
function fixExecutable(dir) {
  let entries = []
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true })
  } catch {
    return
  }
  for (const entry of entries) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      fixExecutable(full)
    } else if (entry.name === 'spawn-helper') {
      try {
        fs.chmodSync(full, 0o755)
      } catch {
        /* 权限设置失败时忽略 */
      }
    }
  }
}

/* 修复 node-pty 的 spawn-helper 权限（Windows 无此文件） */
function fixPtyPermissions() {
  if (process.platform !== 'win32') {
    fixExecutable(path.join(__dirname, '..', 'node_modules', 'node-pty'))
  }
}

let failedCount = 0
for (const step of STEPS) {
  /* Windows 下可执行文件为 .cmd，需要经 shell 解析 */
  const result = spawnSync(step.command, step.args, {
    stdio: 'inherit',
    shell: process.platform === 'win32'
  })
  if (result.status !== 0) {
    failedCount += 1
    console.error(`[postinstall] 步骤失败：${step.command} ${step.args.join(' ')}`.trim())
  }
}

/* 无论前面步骤是否成功都修复权限，避免终端启动时报 posix_spawnp failed */
fixPtyPermissions()

process.exitCode = failedCount > 0 ? 1 : 0
