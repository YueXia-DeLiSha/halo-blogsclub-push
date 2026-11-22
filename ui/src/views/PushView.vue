<script setup lang="ts">
import { ref } from 'vue'

interface SyncResult {
  ok: boolean
  disabled: boolean
  published: number
  pending: number
  pushed: number
  failed: number
  titles: string[]
  message: string
}

const ENDPOINTS = [
  '/apis/api.console.halo.run/v1alpha1/trigger',
]

const loading = ref(false)
const result = ref<SyncResult | null>(null)
const error = ref('')

function csrfToken(): string {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match ? decodeURIComponent(match[1]) : ''
}

async function trigger() {
  loading.value = true
  error.value = ''
  result.value = null
  try {
    let lastStatus = 0
    for (const url of ENDPOINTS) {
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'X-XSRF-TOKEN': csrfToken(), Accept: 'application/json' },
      })
      lastStatus = res.status
      if (res.status === 404) {
        continue
      }
      if (!res.ok) {
        throw new Error('请求失败（HTTP ' + res.status + '）')
      }
      result.value = (await res.json()) as SyncResult
      return
    }
    throw new Error('接口未找到（HTTP ' + lastStatus + '），请重启 Halo 后重试')
  } catch (e: any) {
    error.value = e && e.message ? String(e.message) : String(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="bcp-page">
    <header class="bcp-header">
      <h1>BlogsClub 推送</h1>
      <p>按 BC-PUSH-V2 协议把已发布文章推送到 BlogsClub。定时任务每 5 分钟自动扫描，这里可以立即触发一次。</p>
    </header>

    <div class="bcp-card">
      <button class="bcp-btn" :disabled="loading" @click="trigger">
        {{ loading ? '推送中…' : '立即推送' }}
      </button>
      <span class="bcp-hint">只推送未推送过的文章，已有记录会自动跳过</span>
    </div>

    <div v-if="error" class="bcp-panel bcp-panel--error">
      <strong>推送失败</strong>
      <p>{{ error }}</p>
    </div>

    <div
      v-if="result"
      class="bcp-panel"
      :class="result.disabled ? 'bcp-panel--warn' : 'bcp-panel--ok'"
    >
      <strong>{{ result.message }}</strong>
      <dl class="bcp-stats">
        <div><dt>已发布</dt><dd>{{ result.published }}</dd></div>
        <div><dt>待推送</dt><dd>{{ result.pending }}</dd></div>
        <div><dt>成功</dt><dd>{{ result.pushed }}</dd></div>
        <div><dt>失败</dt><dd>{{ result.failed }}</dd></div>
      </dl>
      <ul v-if="result.titles && result.titles.length" class="bcp-titles">
        <li v-for="title in result.titles" :key="title">{{ title }}</li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.bcp-page {
  padding: 24px;
  max-width: 900px;
}

.bcp-header h1 {
  font-size: 20px;
  font-weight: 600;
  margin: 0 0 8px;
  color: #111827;
}

.bcp-header p {
  margin: 0 0 20px;
  font-size: 13px;
  line-height: 1.7;
  color: #6b7280;
}

.bcp-card {
  display: flex;
  align-items: center;
  gap: 16px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 20px;
}

.bcp-btn {
  flex: none;
  padding: 9px 22px;
  font-size: 14px;
  font-weight: 500;
  color: #fff;
  background: #2563eb;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.bcp-btn:hover:not(:disabled) {
  background: #1d4ed8;
}

.bcp-btn:disabled {
  background: #9ca3af;
  cursor: not-allowed;
}

.bcp-hint {
  font-size: 12px;
  color: #9ca3af;
}

.bcp-panel {
  margin-top: 16px;
  padding: 18px 20px;
  border-radius: 8px;
  border: 1px solid #e5e7eb;
  background: #f9fafb;
}

.bcp-panel--ok {
  border-color: #a7f3d0;
  background: #ecfdf5;
}

.bcp-panel--warn {
  border-color: #fde68a;
  background: #fffbeb;
}

.bcp-panel--error {
  border-color: #fecaca;
  background: #fef2f2;
}

.bcp-panel strong {
  font-size: 14px;
  color: #111827;
}

.bcp-panel p {
  margin: 8px 0 0;
  font-size: 13px;
  color: #4b5563;
  word-break: break-all;
}

.bcp-stats {
  display: flex;
  gap: 28px;
  margin: 14px 0 0;
}

.bcp-stats dt {
  font-size: 12px;
  color: #6b7280;
}

.bcp-stats dd {
  margin: 4px 0 0;
  font-size: 20px;
  font-weight: 600;
  color: #111827;
}

.bcp-titles {
  margin: 14px 0 0;
  padding-left: 18px;
  font-size: 13px;
  color: #374151;
}

.bcp-titles li {
  margin: 4px 0;
}
</style>