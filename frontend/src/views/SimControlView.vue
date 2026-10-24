<template>
  <div class="control-page">
    <!-- 仿真控制 -->
    <h2 style="color:#e0e0e0;margin-bottom:12px;">仿真控制</h2>
    <el-card class="card">
      <p class="status-line">
        状态：<b :style="{color: running?'#0f9':'#e94560'}">{{ running ? '🟢 运行中' : '🔴 已停止' }}</b>
      </p>
      <p class="info">飞机数量：{{ count }} ｜ 报文总数：{{ messages }}</p>
    </el-card>

    <el-button :type="running?'danger':'success'" size="large" @click="toggle" style="width:100%;margin-bottom:12px;">
      {{ running ? '⏹ 停止仿真' : '▶ 启动仿真' }}
    </el-button>

    <el-card class="card" style="margin-bottom:16px;">
      <p style="color:#e0e0e0;margin-bottom:8px;">飞机数量：{{ aircraftCount }}</p>
      <el-slider v-model="aircraftCount" :min="1" :max="50" :step="1" @change="applyCount"/>
      <el-button size="small" @click="applyCount" style="margin-top:8px;">应用</el-button>
    </el-card>

    <!-- 攻击模拟 -->
    <h2 style="color:#e94560;margin-bottom:8px;">⚡ 攻击模拟（独立注入通道）</h2>
    <p style="font-size:11px;color:#8b949e;margin-bottom:8px;">
      通过报文总线注入恶意 hex（source=INJECTED），检测模块纯报文分析、不知道攻击来源
    </p>
    <div class="attack-btns">
      <el-button type="danger" @click="doAttack('position')" :loading="attacking">
        📍 位置欺骗
      </el-button>
      <el-button type="warning" @click="doAttack('altitude')" :loading="attacking">
        📏 高度篡改
      </el-button>
      <el-button type="warning" @click="doAttack('speed')" :loading="attacking">
        ⚡ 速度超限
      </el-button>
      <el-button @click="doAttack('crc')" :loading="attacking" style="border-color:#e94560;color:#e94560;">
        🔐 CRC 篡改
      </el-button>
      <el-button @click="doAttack('replay')" :loading="attacking" style="border-color:#e94560;color:#e94560;">
        🔄 重放攻击
      </el-button>
      <el-button @click="doAttack()" :loading="attacking" type="info" plain>
        🎲 随机攻击
      </el-button>
    </div>

    <!-- 自动演示 -->
    <el-button type="danger" plain size="large" style="width:100%;margin-top:12px;"
      @click="autoDemo" :loading="autoDemoing">
      🎬 自动演示（连续触发 5 种攻击）
    </el-button>
    <div v-if="demoLog.length" class="demo-log">
      <p style="color:#e0e0e0;font-size:12px;margin-bottom:4px;">演示进度：</p>
      <div v-for="(l, i) in demoLog" :key="i" class="attack-log">
        <span style="color:#0f9;">✔</span> {{ l }}
      </div>
    </div>

    <!-- 真实 ADS-B 数据源 -->
    <h2 style="color:#4da3ff;margin:16px 0 8px;">🌐 真实 ADS-B 数据源</h2>
    <p style="font-size:11px;color:#8b949e;margin-bottom:8px;">
      从 OpenSky Network 拉取真实航班（已解码的 ICAO/呼号/位置/高度/速度），接入本系统地图与检测链路
    </p>
    <el-card class="card">
      <div style="display:flex;gap:8px;">
        <el-button type="primary" plain @click="doImportReal" :loading="importingReal">📥 导入真实航班</el-button>
        <el-button type="danger" plain @click="doClearReal" :loading="importingReal">🗑 清除真实航班</el-button>
      </div>
      <div v-if="realMsg" style="margin-top:8px;font-size:12px;color:#c9d1d9;">{{ realMsg }}</div>
    </el-card>

    <!-- 检测效果指标 -->
    <h2 style="color:#0f9;margin:16px 0 8px;">📈 检测效果量化指标</h2>
    <el-card class="card">
      <div class="metric-grid">
        <div class="metric"><span class="mv">{{ pct(metrics.recall) }}</span><span class="ml">检出率 Recall</span></div>
        <div class="metric"><span class="mv">{{ pct(metrics.fpr) }}</span><span class="ml">误报率 FPR</span></div>
        <div class="metric"><span class="mv">{{ pct(metrics.precision) }}</span><span class="ml">精确率 Precision</span></div>
        <div class="metric"><span class="mv">{{ pct(metrics.f1) }}</span><span class="ml">F1 分数</span></div>
      </div>
      <p class="info" style="margin-top:8px;">
        注入报文 {{ metrics.injectedMessages ?? 0 }} 条 ｜ 正常报文 {{ metrics.normalMessages ?? 0 }} 条
        ｜ 命中 {{ metrics.truePositives ?? 0 }} ｜ 误报 {{ metrics.falsePositives ?? 0 }}
      </p>
      <el-button size="small" text @click="loadMetrics" style="margin-top:4px;">🔄 刷新指标</el-button>
    </el-card>

    <!-- 攻击记录 -->
    <div v-if="attackResults.length" style="margin-top:12px;">
      <p style="color:#e0e0e0;font-size:12px;margin-bottom:4px;">攻击记录：</p>
      <div v-for="(r, i) in attackResults" :key="i" class="attack-log">
        <span v-if="r.success" style="color:#0f9;">✔</span>
        <span v-else style="color:#e94560;">✘</span>
        {{ attackLabel(r.attackType) }} → {{ r.victimCallsign || r.victimIcao }}
        <span class="arrow">→</span> <router-link to="/alerts" style="color:#0f9;">查看告警</router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { fetchSimStatus, startSim, stopSim, updateConfig, triggerAttack, clearAlerts, fetchDetectionMetrics, importRealData, clearRealData } from '@/api'

const running = ref(false)
const count = ref(0)
const messages = ref(0)
const aircraftCount = ref(15)
const attacking = ref(false)
const autoDemoing = ref(false)
const attackResults = ref<any[]>([])
const demoLog = ref<string[]>([])
const metrics = ref<any>({})
const importingReal = ref(false)
const realMsg = ref('')

const attackLabels: Record<string, string> = {
  position: '位置欺骗', altitude: '高度篡改', speed: '速度超限',
  crc: 'CRC篡改', replay: '重放攻击'
}
function attackLabel(t: string) { return attackLabels[t] || t }
function pct(v: number) { return v == null ? '—' : (v * 100).toFixed(1) + '%' }
function sleep(ms: number) { return new Promise(r => setTimeout(r, ms)) }

let timer: number

async function load() {
  try {
    const s = await fetchSimStatus()
    running.value = s.running; count.value = s.aircraftCount; messages.value = s.messageCount
    aircraftCount.value = s.aircraftCount
  } catch (_) { /* */ }
}

async function loadMetrics() {
  try { metrics.value = await fetchDetectionMetrics() } catch (_) { /* */ }
}

async function toggle() {
  try {
    running.value ? await stopSim() : await startSim(aircraftCount.value, 1000)
    load()
  } catch (_) { /* */ }
}

async function applyCount() {
  try { await updateConfig(aircraftCount.value); load() } catch (_) { /* */ }
}

async function doAttack(attackType?: string) {
  attacking.value = true
  try {
    const res = await triggerAttack(attackType)
    attackResults.value.unshift(res)
    if (attackResults.value.length > 10) attackResults.value.pop()
    load()
  } catch (_) { /* */ }
  attacking.value = false
}

// 自动演示：清空告警 → 按固定节奏连续触发 5 种攻击 → 汇总检测指标
async function autoDemo() {
  autoDemoing.value = true
  demoLog.value = []
  try { await clearAlerts() } catch (_) { /* */ }
  const types = ['position', 'altitude', 'speed', 'crc', 'replay']
  for (let i = 0; i < types.length; i++) {
    const t = types[i]
    try {
      const res = await triggerAttack(t)
      demoLog.value.unshift(`${i + 1}/5 ${attackLabel(t)} → ${res.victimCallsign || res.victimIcao}`)
    } catch (_) {
      demoLog.value.unshift(`${i + 1}/5 ${attackLabel(t)} 注入失败`)
    }
    await sleep(2500)
  }
  await loadMetrics()
  await load()
  autoDemoing.value = false
}

async function doImportReal() {
  importingReal.value = true
  realMsg.value = ''
  try {
    const r = await importRealData()
    realMsg.value = r.success ? `✅ 已导入 ${r.imported} 架真实航班（地图上蓝色显示）` : `❌ ${r.message || '导入失败'}`
  } catch (_) { realMsg.value = '❌ 导入失败，请检查网络' }
  importingReal.value = false
  load()
}

async function doClearReal() {
  importingReal.value = true
  realMsg.value = ''
  try {
    const r = await clearRealData()
    realMsg.value = `🗑 已清除 ${r.deleted} 架真实航班`
  } catch (_) { realMsg.value = '❌ 清除失败' }
  importingReal.value = false
  load()
}

onMounted(() => { load(); loadMetrics(); timer = window.setInterval(load, 2000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.control-page { padding: 16px; max-width: 640px; }
.card { background: #16213e; border-color: #0f3460; margin-bottom: 12px; }
.status-line { font-size: 18px; margin-bottom: 4px; }
.info { color: #999; font-size: 13px; }
.attack-btns { display: flex; flex-wrap: wrap; gap: 8px; }
.attack-log {
  font-size: 11px; color: #c9d1d9; padding: 2px 0;
  border-bottom: 1px solid #1a1a2e;
}
.arrow { color: #8b949e; margin: 0 4px; }
.demo-log { margin-top: 8px; }
.metric-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.metric { text-align: center; padding: 10px; background: #0f1626; border-radius: 6px; }
.mv { font-size: 22px; font-weight: bold; color: #0f9; display: block; }
.ml { font-size: 11px; color: #999; }
</style>
