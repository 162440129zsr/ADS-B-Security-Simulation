<template>
  <div style="padding:16px;height:100%;overflow-y:auto;">
    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px;">
      <h2 style="color:#e0e0e0;margin:0;">安全告警</h2>
      <el-button type="danger" size="small" @click="doClear" :loading="clearing" plain>
        🗑 清空全部告警
      </el-button>
    </div>

    <!-- 汇总 -->
    <div style="display:flex;gap:12px;margin-bottom:16px;">
      <div v-for="c in counts" :key="c.label"
        style="flex:1;text-align:center;padding:12px;background:#16213e;border-radius:6px;border:1px solid #0f3460;">
        <div style="font-size:22px;font-weight:bold;" :style="{color:c.color}">{{ c.value }}</div>
        <div style="font-size:11px;color:#999;">{{ c.label }}</div>
      </div>
    </div>

    <!-- 分组告警：同一架飞机聚在一起 -->
    <div v-if="groups.length===0" style="text-align:center;color:#666;padding:40px;">✅ 暂无告警</div>
    <div v-for="g in groups" :key="g.icao" class="alert-group">
      <div class="group-header">
        <div style="display:flex;align-items:center;gap:8px;min-width:0;">
          <span class="group-icao">✈ {{ g.icao }}</span>
          <span v-if="g.callsign" class="group-callsign">{{ g.callsign }}</span>
          <el-tag size="small" :type="g.hasRed ? 'danger' : 'warning'">{{ g.count }} 条告警</el-tag>
        </div>
        <span class="group-latest">最近 {{ fmtTime(g.latest) }}</span>
      </div>
      <div v-for="a in g.alerts" :key="a.id" class="alert-item">
        <el-tag :color="levelColor(a.alertLevel)" size="small" effect="dark" style="flex-shrink:0;">{{ a.alertLevel }}</el-tag>
        <span class="alert-type">{{ a.alertType }}</span>
        <span class="alert-desc">{{ a.description }}</span>
        <span class="alert-time">{{ fmtTime(a.createdAt) }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { fetchAlerts, fetchAircraft, clearAlerts } from '@/api'
import { ElMessageBox } from 'element-plus'

const alerts = ref<any[]>([])
const groups = ref<any[]>([])
const clearing = ref(false)
const counts = ref<{label:string;value:number;color:string}[]>([])
let timer: number

function levelColor(l: string) { return l==='红色'?'#e94560':l==='橙色'?'#f0a500':'#f0c040' }
function fmtTime(t: string) {
  if (!t) return '—'
  return t.replace('T', ' ').slice(5, 19)  // "2026-10-24T11:42:15" → "10-24 11:42:15"
}

async function load() {
  try {
    const [alertList, aircraftList] = await Promise.all([
      fetchAlerts(),
      fetchAircraft().catch(() => [] as any[])
    ])
    alerts.value = alertList

    // 汇总
    counts.value = [
      { label: '红色', value: alertList.filter(a=>a.alertLevel==='红色').length, color: '#e94560' },
      { label: '橙色', value: alertList.filter(a=>a.alertLevel==='橙色').length, color: '#f0a500' },
      { label: '黄色', value: alertList.filter(a=>a.alertLevel==='黄色').length, color: '#f0c040' },
      { label: '总计', value: alertList.length, color: '#0f9' }
    ]

    // icao → 呼号映射
    const callsignMap: Record<string, string> = {}
    aircraftList.forEach((ac: any) => { if (ac.icao) callsignMap[ac.icao] = ac.callsign || '' })

    // 按 icao 分组（alertList 已按时间降序，组内天然有序）
    const byIcao: Record<string, any[]> = {}
    alertList.forEach(a => { (byIcao[a.icao] ||= []).push(a) })
    groups.value = Object.entries(byIcao).map(([icao, list]) => ({
      icao,
      callsign: callsignMap[icao] || '',
      alerts: list,
      count: list.length,
      latest: list[0]?.createdAt || '',
      hasRed: list.some(a => a.alertLevel === '红色')
    })).sort((a, b) => (b.latest || '').localeCompare(a.latest || ''))
  } catch (_) { /* */ }
}

async function doClear() {
  try {
    await ElMessageBox.confirm('确定清空所有告警记录？此操作不可恢复。', '确认', { type: 'warning' })
    clearing.value = true
    await clearAlerts()
    load()
  } catch (_) { /* 取消 */ }
  clearing.value = false
}

onMounted(() => { load(); timer = window.setInterval(load, 2000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.alert-group {
  background: #16213e;
  border: 1px solid #0f3460;
  border-radius: 8px;
  margin-bottom: 12px;
  overflow: hidden;
}
.group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: rgba(15, 52, 96, 0.35);
  border-bottom: 1px solid #0f3460;
}
.group-icao {
  font-size: 15px;
  font-weight: bold;
  color: #0f9;
  font-family: 'JetBrains Mono', Consolas, monospace;
}
.group-callsign {
  font-size: 12px;
  color: #c9d1d9;
  background: rgba(255,255,255,.06);
  padding: 1px 8px;
  border-radius: 4px;
}
.group-latest {
  font-size: 11px;
  color: #8b949e;
  white-space: nowrap;
}
.alert-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  border-bottom: 1px solid rgba(255,255,255,.04);
  font-size: 12px;
}
.alert-item:last-child { border-bottom: none; }
.alert-type {
  color: #e0e0e0;
  font-weight: 600;
  white-space: nowrap;
  min-width: 90px;
}
.alert-desc {
  color: #8b949e;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.alert-time {
  color: #666;
  font-size: 11px;
  white-space: nowrap;
}
</style>
