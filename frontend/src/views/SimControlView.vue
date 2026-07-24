<template>
  <div style="padding:16px;max-width:500px;">
    <h2 style="color:#e0e0e0;margin-bottom:16px;">仿真控制</h2>
    <el-card style="background:#16213e;border-color:#0f3460;margin-bottom:12px;">
      <p style="color:#0f9;font-size:18px;margin-bottom:8px;">
        状态：<b>{{ running ? '🟢 运行中' : '🔴 已停止' }}</b>
      </p>
      <p style="color:#999;">飞机数量：{{ count }}</p>
      <p style="color:#999;">报文总数：{{ messages }}</p>
    </el-card>

    <el-button :type="running?'danger':'success'" size="large" @click="toggle" style="width:100%;margin-bottom:12px;">
      {{ running ? '⏹ 停止仿真' : '▶ 启动仿真' }}
    </el-button>

    <el-card style="background:#16213e;border-color:#0f3460;">
      <p style="color:#e0e0e0;margin-bottom:8px;">飞机数量：{{ aircraftCount }}</p>
      <el-slider v-model="aircraftCount" :min="1" :max="50" :step="1" @change="applyCount"/>
      <el-button size="small" @click="applyCount" style="margin-top:8px;">应用</el-button>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { fetchSimStatus, startSim, stopSim, updateConfig } from '@/api'

const running = ref(false)
const count = ref(0)
const messages = ref(0)
const aircraftCount = ref(15)

async function load() {
  const s = await fetchSimStatus()
  running.value = s.running; count.value = s.aircraftCount; messages.value = s.messageCount
  aircraftCount.value = s.aircraftCount
}
async function toggle() { running.value ? await stopSim() : await startSim(aircraftCount.value, 1000); load() }
async function applyCount() { await updateConfig(aircraftCount.value); load() }

onMounted(() => { load(); setInterval(load, 2000) })
</script>
