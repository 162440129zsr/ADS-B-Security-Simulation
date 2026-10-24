<template>
  <div class="station-page">
    <!-- ====== 站点状态报告（CAT023 风格） ====== -->
    <h2 style="color:#e0e0e0;margin-bottom:12px;">地面站状态报告（CAT023 风格）</h2>

    <el-card class="card status-card" :style="{borderColor: statusColor}">
      <div class="status-main">
        <span class="status-light" :style="{background: statusColor, boxShadow:`0 0 12px ${statusColor}`}"></span>
        <div>
          <div class="status-name">{{ station.stationName || 'ADS-B 仿真地面站' }}</div>
          <div class="status-value" :style="{color: statusColor}">{{ statusText }}</div>
        </div>
      </div>
      <div class="status-grid">
        <div class="sg"><label>目标数</label><b>{{ station.targetCount ?? 0 }}</b><span>/ {{ station.capacity ?? 0 }} 批</span></div>
        <div class="sg"><label>报文总数</label><b>{{ station.messageCount ?? 0 }}</b></div>
        <div class="sg"><label>GNSS 状态</label><b style="color:#0f9;">{{ station.gnssStatus || '正常' }}</b></div>
        <div class="sg"><label>仿真运行</label><b :style="{color: station.simulationRunning ? '#0f9' : '#e94560'}">{{ station.simulationRunning ? '运行中' : '已停止' }}</b></div>
      </div>
      <div class="legend">
        <span><i style="background:#2d6a4f;"></i> 良好</span>
        <span><i style="background:#f0a500;"></i> 降级</span>
        <span><i style="background:#e94560;"></i> 过载</span>
        <span><i style="background:#8b0000;"></i> 故障</span>
      </div>
    </el-card>

    <!-- ====== 信标机自检 ====== -->
    <h2 style="color:#0f9;margin:20px 0 12px;">信标机自检目标</h2>
    <p style="font-size:11px;color:#8b949e;margin-bottom:10px;">
      信标机周期发射测试信号，用于检测地面站接收性能。当天馈系统性能下降、信号强度低于门限时产生告警。
    </p>
    <el-card class="card">
      <div class="beacon-row">
        <div>
          <div style="font-size:14px;color:#e0e0e0;"><b>{{ beacon?.icao || 'BEA001' }}</b> · {{ beacon?.callsign || '信标机' }}</div>
          <div style="font-size:12px;margin-top:4px;">
            状态：<el-tag :type="beacon?.status==='正常'?'success':'warning'" size="small">{{ beacon?.status || '—' }}</el-tag>
          </div>
        </div>
        <div class="sig-meter">
          <div class="sig-value" :style="{color: sigColor}">{{ beacon?.signalStrength?.toFixed(0) ?? '—' }}%</div>
          <div class="sig-thr">门限 {{ beacon?.threshold?.toFixed(0) ?? 30 }}%</div>
        </div>
      </div>

      <el-divider style="border-color:#0f3460;margin:12px 0;"/>

      <p style="color:#e0e0e0;font-size:12px;margin-bottom:8px;">
        模拟天馈性能变化：<b style="color:#0f9;">{{ beacon?.signalStrength?.toFixed(0) }}%</b>
      </p>
      <el-slider v-model="strength" :min="0" :max="100" :step="5" @change="applyStrength"/>
      <div class="preset-btns">
        <el-button size="small" type="success" plain @click="applyPreset(85)">恢复正常 (85%)</el-button>
        <el-button size="small" type="warning" plain @click="applyPreset(20)">性能下降 (20%)</el-button>
        <el-button size="small" type="danger" plain @click="applyPreset(5)">严重故障 (5%)</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { fetchStationStatus, fetchBeacon, setBeaconSignal } from '@/api'

const station = ref<any>({})
const beacon = ref<any>({})
const strength = ref(85)
let timer: number

const statusText = computed(() => {
  const m: Record<string, string> = { '良好': '运行良好', '降级': '性能降级', '过载': '目标过载', '故障': '设备故障' }
  return m[station.value.workingStatus] || station.value.workingStatus || '—'
})
const statusColor = computed(() => {
  return { '良好': '#2d6a4f', '降级': '#f0a500', '过载': '#e94560', '故障': '#8b0000' }[station.value.workingStatus] || '#999'
})
const sigColor = computed(() => {
  const s = beacon.value.signalStrength
  const t = beacon.value.threshold
  return s != null && t != null && s < t ? '#e94560' : '#0f9'
})

async function load() {
  try { station.value = await fetchStationStatus() } catch (_) {}
  try {
    beacon.value = await fetchBeacon()
    strength.value = beacon.value.signalStrength ?? 85
  } catch (_) {}
}

async function applyStrength() {
  try { beacon.value = await setBeaconSignal(strength.value) } catch (_) {}
  await load()
}
async function applyPreset(v: number) {
  strength.value = v
  await applyStrength()
}

onMounted(() => { load(); timer = window.setInterval(load, 2000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.station-page { padding: 16px; height: 100%; overflow-y: auto; max-width: 720px; }
.card { background: #16213e; border-color: #0f3460; }

.status-card { border-left: 3px solid; }
.status-main { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.status-light { width: 14px; height: 14px; border-radius: 50%; display: inline-block; }
.status-name { font-size: 13px; color: #8b949e; }
.status-value { font-size: 24px; font-weight: bold; }

.status-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 12px; }
.sg { text-align: center; padding: 10px; background: #0f1626; border-radius: 6px; }
.sg label { display: block; font-size: 11px; color: #8b949e; margin-bottom: 4px; }
.sg b { font-size: 18px; color: #e0e0e0; }
.sg span { font-size: 10px; color: #8b949e; }

.legend { display: flex; gap: 16px; font-size: 11px; color: #8b949e; }
.legend i { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 4px; }

.beacon-row { display: flex; justify-content: space-between; align-items: center; }
.sig-meter { text-align: center; }
.sig-value { font-size: 28px; font-weight: bold; }
.sig-thr { font-size: 11px; color: #8b949e; }
.preset-btns { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px; }
</style>
