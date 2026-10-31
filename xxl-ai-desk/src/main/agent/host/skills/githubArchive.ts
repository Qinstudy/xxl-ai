/*
 * GitHub 归档缓存：下载目录、元信息、30 分钟复用与清理。
 *   - 下载与解压由 githubDownload 负责；
 *   - 目录固定为 {数据目录}/tmp/github-owner-repo，便于人工清理；
 *   - 同一仓库 30 分钟内复用，超时或 ref 变化时重新下载。
 * @author xuxueli 2026-10-31
 */

import {
  existsSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync
} from 'fs'
import { join } from 'path'
import { ensureTempDir, getDataDir, getSkillsDir, getTempDir } from '../../../config/paths'
import { downloadGithubArchive } from './githubDownload'

/* 归档缓存有效期（毫秒）：同仓库重复下载复用 30 分钟 */
const CACHE_TTL = 30 * 60 * 1000

/* 下载目录名前缀：github-owner-repo */
const DOWNLOAD_DIR_PREFIX = 'github-'

/* 下载目录元信息文件 */
const META_FILE = 'meta.json'

/* 已解压归档 */
export interface GithubArchive {
  /* 归档临时目录（含解压内容与 meta.json） */
  tempDir: string
  /* 解压后的仓库根目录 */
  rootDir: string
  /* 过期时间 */
  expireAt: number
}

/* 缓存、进行中的下载与取消控制器 */
const cache = new Map<string, GithubArchive>()
const pending = new Map<string, Promise<GithubArchive>>()
const controllers = new Map<string, AbortController>()

/* 生成缓存 key */
function archiveKey(repo: string, ref: string): string {
  return `${repo}@${ref || 'HEAD'}`
}

/* 下载目录名：tmp/github-owner-repo */
function downloadDirName(repo: string): string {
  const safe = repo
    .trim()
    .replace(/[\\/]+/g, '-')
    .replace(/[^\p{L}\p{N}._-]+/gu, '-')
    .replace(/^-+|-+$/g, '')
  return `${DOWNLOAD_DIR_PREFIX}${safe || 'repo'}`
}

/* 下载目录元信息 */
interface DownloadMeta {
  /* owner/repo */
  repo: string
  /* 分支、标签或提交 */
  ref: string
  /* 创建时间（毫秒） */
  createdAt: number
}

/* 读取目录元信息（缺失或非法返回 null） */
function readDownloadMeta(tempDir: string): DownloadMeta | null {
  try {
    const parsed = JSON.parse(readFileSync(join(tempDir, META_FILE), 'utf-8')) as DownloadMeta
    if (
      parsed &&
      typeof parsed.repo === 'string' &&
      typeof parsed.ref === 'string' &&
      Number.isFinite(parsed.createdAt)
    ) {
      return parsed
    }
  } catch {
    /* 元信息不存在或损坏 */
  }
  return null
}

/* 写入目录元信息 */
function writeDownloadMeta(tempDir: string, repo: string, ref: string, createdAt: number): void {
  writeFileSync(join(tempDir, META_FILE), JSON.stringify({ repo, ref, createdAt }), 'utf-8')
}

/* 目录是否已超过缓存时间 */
function isExpired(createdAt: number): boolean {
  return Date.now() - createdAt > CACHE_TTL
}

/* 读取目录创建时间：优先 meta，其次文件系统创建时间 */
function dirCreatedAt(dir: string): number {
  const meta = readDownloadMeta(dir)
  if (meta) {
    return meta.createdAt
  }
  try {
    const info = statSync(dir)
    return info.birthtimeMs || info.mtimeMs || Date.now()
  } catch {
    return Date.now()
  }
}

/* 清理过期缓存 */
function pruneCache(): void {
  const now = Date.now()
  for (const [key, item] of cache) {
    if (item.expireAt <= now) {
      cache.delete(key)
      rmSync(item.tempDir, { recursive: true, force: true })
    }
  }
}

/* 删除指定目录下匹配前缀 / 时间戳命名的下载目录 */
function removeDownloadDirs(dir: string, match: (name: string) => boolean): void {
  try {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.isDirectory() && match(entry.name)) {
        rmSync(join(dir, entry.name), { recursive: true, force: true })
      }
    }
  } catch {
    /* 目录不存在时无需清理 */
  }
}

/* 清理启动时已过期的下载目录，并兼容旧版临时目录 */
export function cleanupGithubArchiveTemps(): void {
  ensureTempDir()
  /* 当前 tmp：只删除超过 30 分钟的 github-owner-repo */
  try {
    for (const entry of readdirSync(getTempDir(), { withFileTypes: true })) {
      if (!entry.isDirectory() || !entry.name.startsWith(DOWNLOAD_DIR_PREFIX)) {
        continue
      }
      const target = join(getTempDir(), entry.name)
      if (isExpired(dirCreatedAt(target))) {
        rmSync(target, { recursive: true, force: true })
      }
    }
  } catch {
    /* 临时目录不存在时无需清理 */
  }

  /* 兼容旧版 .tmp 与 skills/.tmp-github-* 遗留目录 */
  const legacyTmp = join(getDataDir(), '.tmp')
  removeDownloadDirs(
    legacyTmp,
    (name) => name.startsWith(DOWNLOAD_DIR_PREFIX) || /^\d{8}-\d{6}-\d{3}-/.test(name)
  )
  removeDownloadDirs(getSkillsDir(), (name) => name.startsWith('.tmp-github-'))
}

/* 仓库对应的下载目录 */
function archivePaths(repo: string): { tempDir: string; rootDir: string } {
  const tempDir = join(getTempDir(), downloadDirName(repo))
  return { tempDir, rootDir: join(tempDir, 'root') }
}

/* 复用 30 分钟内的已解压目录；目录过期、仓库/分支不匹配或内容为空时删除 */
function reuseArchive(repo: string, ref: string): GithubArchive | null {
  const { tempDir, rootDir } = archivePaths(repo)
  if (!existsSync(tempDir)) {
    return null
  }
  const meta = readDownloadMeta(tempDir)
  const createdAt = meta?.createdAt ?? dirCreatedAt(tempDir)
  if (!meta || meta.repo !== repo || meta.ref !== ref || isExpired(createdAt)) {
    rmSync(tempDir, { recursive: true, force: true })
    return null
  }
  if (!existsSync(rootDir) || readdirSync(rootDir).length === 0) {
    rmSync(tempDir, { recursive: true, force: true })
    return null
  }
  return {
    tempDir,
    rootDir,
    expireAt: createdAt + CACHE_TTL
  }
}

/* 下载并解压归档 */
async function prepareArchive(
  repo: string,
  ref: string,
  signal?: AbortSignal
): Promise<GithubArchive> {
  ensureTempDir()
  const { tempDir, rootDir } = archivePaths(repo)
  /* acquire 已判定不可复用，重新下载前清空旧目录 */
  rmSync(tempDir, { recursive: true, force: true })
  const createdAt = Date.now()
  try {
    await downloadGithubArchive(repo, ref, tempDir, signal)
    writeDownloadMeta(tempDir, repo, ref, createdAt)
    return {
      tempDir,
      rootDir,
      expireAt: createdAt + CACHE_TTL
    }
  } catch (error) {
    rmSync(tempDir, { recursive: true, force: true })
    throw error
  }
}

/* 获取归档：内存缓存 → 30 分钟内磁盘缓存 → 重新下载 */
export async function acquireGithubArchive(
  repo: string,
  ref: string,
  signal?: AbortSignal
): Promise<GithubArchive> {
  const key = archiveKey(repo, ref)
  pruneCache()
  const cached = cache.get(key)
  if (cached && existsSync(cached.rootDir)) {
    return cached
  }
  if (cached) {
    cache.delete(key)
    rmSync(cached.tempDir, { recursive: true, force: true })
  }

  const reused = reuseArchive(repo, ref)
  if (reused) {
    cache.set(key, reused)
    pruneCache()
    return reused
  }

  const running = pending.get(key)
  if (running) {
    return running
  }
  const controller = new AbortController()
  controllers.set(key, controller)
  if (signal) {
    if (signal.aborted) {
      controller.abort()
    } else {
      signal.addEventListener('abort', () => controller.abort(), { once: true })
    }
  }
  const task = prepareArchive(repo, ref, controller.signal)
    .then((archive) => {
      if (controller.signal.aborted) {
        rmSync(archive.tempDir, { recursive: true, force: true })
        throw new Error('已取消 GitHub 归档下载')
      }
      cache.set(key, archive)
      pruneCache()
      return archive
    })
    .finally(() => {
      if (pending.get(key) === task) {
        pending.delete(key)
      }
      if (controllers.get(key) === controller) {
        controllers.delete(key)
      }
    })
  pending.set(key, task)
  return task
}

/* 中止指定仓库仍在进行的下载，但保留已完成的 30 分钟缓存 */
export function cancelGithubArchive(repo: string, ref: string): void {
  const key = archiveKey(repo, ref)
  controllers.get(key)?.abort()
  controllers.delete(key)
  pending.delete(key)
}

/* 删除指定仓库的归档缓存（含正在进行的下载） */
export function disposeGithubArchive(repo: string, ref: string): void {
  const key = archiveKey(repo, ref)
  controllers.get(key)?.abort()
  controllers.delete(key)
  pending.delete(key)
  const item = cache.get(key)
  cache.delete(key)
  if (item) {
    rmSync(item.tempDir, { recursive: true, force: true })
  }
}
