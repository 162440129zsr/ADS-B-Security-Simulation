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
    <h2 style="color:#e94560;margin-bottom:8px;">⚡ 攻击模拟（制造异常）</h2>
    <p style="font-size:11px;color:#8b949e;margin-bottom:8px;">
      随机选一架飞机注入异常数据，安全检测模块会自动触发告警
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
import { ref, onMounted } from 'vue'
import { fetchSimStatus, startSim, stopSim, updateConfig, triggerAttack } from '@/api'

const running = ref(false)
const count = ref(0)
const messages = ref(0)
const aircraftCount = ref(15)
const attacking = ref(false)
const attackResults = ref<any[]>([])

const attackLabels: Record<string, string> = {
  position: '位置欺骗', altitude: '高度篡改', speed: '速度超限',
  crc: 'CRC篡改', replay: '重放攻击'
}
function attackLabel(t: string) { return attackLabels[t] || t }

async function load() {
  try {
    const s = await fetchSimStatus()
    running.value = s.running; count.value = s.aircraftCount; messages.value = s.messageCount
    aircraftCount.value = s.aircraftCount
  } catch (_) { /* */ }
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

onMounted(() => { load(); setInterval(load, 2000) })
</script>

<style scoped>
.control-page { padding: 16px; max-width: 600px; }
.card { background: #16213e; border-color: #0f3460; margin-bottom: 12px; }
.status-line { font-size: 18px; margin-bottom: 4px; }
.info { color: #999; font-size: 13px; }
.attack-btns { display: flex; flex-wrap: wrap; gap: 8px; }
.attack-log {
  font-size: 11px; color: #c9d1d9; padding: 2px 0;
  border-bottom: 1px solid #1a1a2e;
}
.arrow { color: #8b949e; margin: 0 4px; }
</style>
