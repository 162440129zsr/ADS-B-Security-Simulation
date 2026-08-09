<template>
  <div style="padding:16px;height:100%;overflow-y:auto;">
    <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px;">
      <h2 style="color:#e0e0e0;margin:0;">安全告警</h2>
      <el-button type="danger" size="small" @click="doClear" :loading="clearing" plain>
        🗑 清空全部告警
      </el-button>
    </div>
    <div style="display:flex;gap:12px;margin-bottom:12px;">
      <div v-for="c in counts" :key="c.label"
        style="flex:1;text-align:center;padding:12px;background:#16213e;border-radius:6px;border:1px solid #0f3460;">
        <div style="font-size:22px;font-weight:bold;" :style="{color:c.color}">{{ c.value }}</div>
        <div style="font-size:11px;color:#999;">{{ c.label }}</div>
      </div>
    </div>
    <el-table :data="alerts" style="width:100%;" size="small" stripe>
      <el-table-column prop="alertType" label="告警类型" width="140"/>
      <el-table-column prop="icao" label="ICAO" width="100"/>
      <el-table-column label="等级" width="80">
        <template #default="{row}">
          <el-tag :color="levelColor(row.alertLevel)" size="small">{{ row.alertLevel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="200"/>
      <el-table-column label="时间" width="160">
        <template #default="{row}">{{ row.createdAt }}</template>
      </el-table-column>
    </el-table>
    <div v-if="alerts.length===0" style="text-align:center;color:#666;padding:40px;">✅ 暂无告警</div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { fetchAlerts, clearAlerts } from '@/api'
import { ElMessageBox } from 'element-plus'

const alerts = ref<any[]>([])
const clearing = ref(false)
const counts = ref<{label:string;value:number;color:string}[]>([])

function levelColor(l: string) { return l==='红色'?'#e94560':l==='橙色'?'#f0a500':'#f0c040' }

async function load() {
  alerts.value = await fetchAlerts()
  counts.value = [
    { label: '红色', value: alerts.value.filter(a=>a.alertLevel==='红色').length, color: '#e94560' },
    { label: '橙色', value: alerts.value.filter(a=>a.alertLevel==='橙色').length, color: '#f0a500' },
    { label: '黄色', value: alerts.value.filter(a=>a.alertLevel==='黄色').length, color: '#f0c040' },
    { label: '总计', value: alerts.value.length, color: '#0f9' }
  ]
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

onMounted(() => { load(); setInterval(load, 3000) })
</script>
