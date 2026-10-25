/*
 * GitHub 技能安装器：按 skills.sh 的 source / skillId 定位仓库内技能目录并下载到本地。
 *   - 只访问 GitHub 固定域名（api.github.com），经 Electron net 走系统代理；
 *   - 通过 Git Tree 获取文件 SHA，再用 Blob 接口按需下载，避免下载整仓；
 *   - 先落临时目录，校验 SKILL.md 后原子移动，并限制单文件与总体积（文件数不设卡）。
 */

import { mkdirSync, rmSync } from 'fs'
import { promises as fs } from 'fs'
import { net } from 'electron'
import { dirname, isAbsolute, join, relative, resolve } from 'path'
import { ensureSkillsDir, getSkillsDir } from '../../../config/paths'
import {
  parseSkillMeta,
  uniqueSkillId,
  type ImportedSkill
} from '../../../services/skillImportService'
import { SKILL_MAX_BYTES, SKILL_MAX_FILE_BYTES } from '../../../services/skillLimits'

/* GitHub API 根地址 */
const GITHUB_API = 'https://api.github.com'

/* 请求超时（毫秒） */
const TIMEOUT = 20000

/* 仓库信息 / 目录树缓存时间（毫秒） */
const CACHE_TTL = 10 * 60 * 1000

/* 下载并发数 */
const CONCURRENCY = 4

/* 仓库标识 */
interface RepoRef {
  owner: string
  repo: string
}

/* 仓库文件节点（路径 + blob SHA + 体积，体积用于下载前预检） */
interface RepoFile {
  path: string
  sha: string
  size: number
}

/* 仓库信息缓存：owner/repo → 默认分支与过期时间 */
const repoCache = new Map<string, { branch: string; expireAt: number }>()

/* 目录树缓存：owner/repo@branch → 文件节点集合 */
const treeCache = new Map<string, { files: RepoFile[]; expireAt: number }>()

/*
 * GitHub 请求：使用 Electron net（自动走系统代理），返回原始字节。
 * 统一超时与可读错误提示，避免把底层异常直接暴露给用户。
 */
function requestBuffer(url: string, accept: string): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const request = net.request({ url, method: 'GET', redirect: 'follow' })
    request.setHeader('User-Agent', 'xxl-ai-desk')
    request.setHeader('Accept', accept)
    request.setHeader('X-GitHub-Api-Version', '2022-11-28')
    const timer = setTimeout(() => {
      request.abort()
      reject(new Error('GitHub 访问超时，请检查网络后重试'))
    }, TIMEOUT)
    request.on('response', (response) => {
      const status = response.statusCode ?? 0
      const chunks: Buffer[] = []
      response.on('data', (chunk: Buffer) => chunks.push(chunk))
      response.on('error', (error) => {
        clearTimeout(timer)
        reject(new Error(`GitHub 响应读取失败：${error.message}`))
      })
      response.on('end', () => {
        clearTimeout(timer)
        const body = Buffer.concat(chunks)
        if (status < 200 || status >= 300) {
          if (status === 404) {
            reject(new Error('仓库或技能不存在（GitHub 404）'))
          } else if (status === 403 || status === 429) {
            reject(new Error('GitHub 请求过于频繁，请稍后重试'))
          } else {
            reject(new Error(`GitHub 访问失败（HTTP ${status}）`))
          }
          return
        }
        resolve(body)
      })
    })
    request.on('error', (error) => {
      clearTimeout(timer)
      reject(new Error(`GitHub 网络请求失败：${error.message}`))
    })
    request.end()
  })
}

/* 请求 JSON 并解析 */
async function requestJson<T>(url: string): Promise<T> {
  const buffer = await requestBuffer(url, 'application/vnd.github+json')
  try {
    return JSON.parse(buffer.toString('utf-8')) as T
  } catch {
    throw new Error('GitHub 返回数据格式异常')
  }
}

/* 读取仓库默认分支（10 分钟缓存） */
async function getDefaultBranch(owner: string, repo: string): Promise<string> {
  const key = `${owner}/${repo}`
  const cached = repoCache.get(key)
  if (cached && cached.expireAt > Date.now()) {
    return cached.branch
  }
  const payload = await requestJson<{ default_branch?: unknown }>(
    `${GITHUB_API}/repos/${owner}/${repo}`
  )
  const branch = String(payload.default_branch ?? '').trim()
  if (!branch) {
    throw new Error('无法读取仓库默认分支')
  }
  repoCache.set(key, { branch, expireAt: Date.now() + CACHE_TTL })
  return branch
}

/* 读取仓库全部文件节点（含 SHA，10 分钟缓存） */
async function listRepoFiles(owner: string, repo: string, branch: string): Promise<RepoFile[]> {
  const key = `${owner}/${repo}@${branch}`
  const cached = treeCache.get(key)
  if (cached && cached.expireAt > Date.now()) {
    return cached.files
  }
  const payload = await requestJson<{ tree?: unknown }>(
    `${GITHUB_API}/repos/${owner}/${repo}/git/trees/${encodeURIComponent(branch)}?recursive=1`
  )
  const tree = Array.isArray(payload.tree) ? payload.tree : []
  const files = tree
    .map((node) => node as { path?: unknown; type?: unknown; sha?: unknown; size?: unknown })
    .filter(
      (node) =>
        node.type === 'blob' && typeof node.path === 'string' && typeof node.sha === 'string'
    )
    .map((node) => ({
      path: String(node.path),
      sha: String(node.sha),
      size: Number(node.size ?? 0) || 0
    }))
  treeCache.set(key, { files, expireAt: Date.now() + CACHE_TTL })
  return files
}

/* 在仓库中定位技能目录：目录名等于 skillId 且其下含 SKILL.md */
function locateSkillDir(files: RepoFile[], skillId: string): string {
  const candidates: string[] = []
  for (const file of files) {
    if (!file.path.endsWith('/SKILL.md')) {
      continue
    }
    const dir = file.path.slice(0, -'/SKILL.md'.length)
    const segments = dir.split('/')
    if (segments[segments.length - 1] !== skillId) {
      continue
    }
    candidates.push(dir)
  }
  if (candidates.length === 0) {
    /* 单技能仓库：SKILL.md 直接位于仓库根目录 */
    if (files.some((file) => file.path === 'SKILL.md')) {
      return ''
    }
    throw new Error(`仓库中未找到技能目录「${skillId}」`)
  }
  /* 优先标准 skills/{skillId}，其次层级最浅、路径最短 */
  candidates.sort((a, b) => {
    const preferredA = a === `skills/${skillId}` ? 0 : 1
    const preferredB = b === `skills/${skillId}` ? 0 : 1
    if (preferredA !== preferredB) {
      return preferredA - preferredB
    }
    const depth = a.split('/').length - b.split('/').length
    return depth !== 0 ? depth : a.length - b.length
  })
  return candidates[0]
}

/* 路径是否位于目标目录内 */
function isInside(root: string, target: string): boolean {
  const rel = relative(resolve(root), resolve(target))
  return rel === '' || (!rel.startsWith('..') && !isAbsolute(rel))
}

/* 下载单个 blob（Git Blob API 原生返回内容，避免 raw 域名不可达） */
async function downloadBlob(
  repoRef: RepoRef,
  sha: string,
  path: string,
  dest: string
): Promise<number> {
  const buffer = await requestBuffer(
    `${GITHUB_API}/repos/${repoRef.owner}/${repoRef.repo}/git/blobs/${sha}`,
    'application/vnd.github.raw'
  )
  if (buffer.byteLength > SKILL_MAX_FILE_BYTES) {
    throw new Error(
      `技能文件过大（${path}，实际 ${Math.ceil(buffer.byteLength / 1024 / 1024)}MB，上限 ${SKILL_MAX_FILE_BYTES / 1024 / 1024}MB）`
    )
  }
  mkdirSync(dirname(dest), { recursive: true })
  await fs.writeFile(dest, buffer)
  return buffer.byteLength
}

/* 并发下载技能目录下全部文件 */
async function downloadSkillFiles(
  repoRef: RepoRef,
  dir: string,
  target: string,
  files: RepoFile[]
): Promise<void> {
  const prefix = dir ? `${dir}/` : ''
  const relativeFiles = files
    .filter((file) => file.path.startsWith(prefix))
    .map((file) => ({ ...file, path: file.path.slice(prefix.length) }))
  if (relativeFiles.length === 0) {
    throw new Error('技能目录为空')
  }
  /* 下载前按目录树体积预检，超限直接失败，避免下载到一半才中断 */
  const planned = relativeFiles.reduce((sum, file) => sum + file.size, 0)
  if (planned > SKILL_MAX_BYTES) {
    throw new Error(
      `技能体积过大（预检约 ${Math.ceil(planned / 1024 / 1024)}MB，上限 ${SKILL_MAX_BYTES / 1024 / 1024}MB）`
    )
  }

  let total = 0
  let cursor = 0
  async function worker(): Promise<void> {
    while (cursor < relativeFiles.length) {
      const index = cursor
      cursor += 1
      const file = relativeFiles[index]
      const dest = resolve(target, file.path)
      if (!isInside(target, dest)) {
        throw new Error(`技能文件路径非法：${file.path}`)
      }
      const size = await downloadBlob(repoRef, file.sha, file.path, dest)
      total += size
      if (total > SKILL_MAX_BYTES) {
        throw new Error(
          `技能体积过大（实际已下载 ${Math.ceil(total / 1024 / 1024)}MB，上限 ${SKILL_MAX_BYTES / 1024 / 1024}MB）`
        )
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, relativeFiles.length) }, () => worker()))
}

/*
 * 安装 GitHub 技能：下载到临时目录 → 解析校验 → 原子移动到 skills/{id}。
 * 返回落位后的技能信息，数据库登记由 skillDiscoverService 完成。
 */
export async function installGitHubSkill(source: string, skillId: string): Promise<ImportedSkill> {
  const matched = source.match(/^([\w.-]+)\/([\w.-]+)$/)
  if (!matched || !/^[\w.-]+$/.test(skillId)) {
    throw new Error('技能来源格式不支持（仅支持 GitHub 仓库）')
  }
  const repoRef: RepoRef = { owner: matched[1], repo: matched[2] }
  ensureSkillsDir()

  const branch = await getDefaultBranch(repoRef.owner, repoRef.repo)
  const files = await listRepoFiles(repoRef.owner, repoRef.repo, branch)
  const dir = locateSkillDir(files, skillId)

  const temp = join(
    getSkillsDir(),
    `.tmp-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
  )
  const staged = join(temp, 'skill')
  mkdirSync(staged, { recursive: true })
  try {
    await downloadSkillFiles(repoRef, dir, staged, files)
    const meta = parseSkillMeta(staged, skillId)
    const id = uniqueSkillId(meta.name)
    const target = join(getSkillsDir(), id)
    await fs.rename(staged, target)
    return { id, dir: target, ...meta }
  } finally {
    rmSync(temp, { recursive: true, force: true })
  }
}
