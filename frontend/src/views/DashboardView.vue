<template>
  <div style="display:flex;flex-direction:column;height:100%;">
    <!-- 顶部栏 -->
    <div style="background:#16213e;padding:10px 16px;display:flex;align-items:center;gap:24px;border-bottom:1px solid #0f3460;">
      <span style="font-size:16px;font-weight:bold;color:#e94560;">✈ 飞行器监视</span>
      <div v-for="s in statCards" :key="s.label" style="text-align:center;">
        <div style="font-size:18px;font-weight:bold;color:#0f9;">{{ s.value }}</div>
        <div style="font-size:10px;color:#999;">{{ s.label }}</div>
      </div>
      <el-button size="small" :type="simRunning?'danger':'success'" @click="toggleSim" style="margin-left:auto;">
        {{ simRunning ? '⏹ 停止' : '▶ 启动' }}
      </el-button>
    </div>
    <!-- 地图 -->
    <div style="flex:1;position:relative;">
      <div id="map" style="width:100%;height:100%;"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { fetchPositions, fetchStats, fetchSimStatus, startSim, stopSim } from '@/api'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

const simRunning = ref(false)
const statCards = ref([
  { label: '在线飞行器', value: 0 },
  { label: '报文总数', value: 0 },
  { label: '异常', value: 0 }
])

let map: L.Map, markers: Record<string, L.Marker> = {}, timer: number

function planeIcon(h: number, s: string) {
  return L.divIcon({
    html: `<div style="transform:rotate(${h||0}deg);font-size:22px;color:${s==='异常'?'#e94560':'#0f9'};">✈</div>`,
    className: 'plane-icon', iconSize: [28, 28], iconAnchor: [14, 14]
  })
}

async function refresh() {
  const [pos, st, status] = await Promise.all([fetchPositions(), fetchStats(), fetchSimStatus()])
  simRunning.value = status.running
  statCards.value = [
    { label: '在线飞行器', value: pos.length },
    { label: '报文总数', value: st.totalMessages },
    { label: '异常', value: st.abnormalAircraft }
  ]
  const seen = new Set<string>()
  for (const ac of pos) {
    if (!ac.latitude) continue
    seen.add(ac.icao)
    const ll: [number, number] = [ac.latitude, ac.longitude]
    if (markers[ac.icao]) {
      markers[ac.icao].setLatLng(ll)
      markers[ac.icao].setIcon(planeIcon(ac.heading, ac.status))
    } else {
      markers[ac.icao] = L.marker(ll, { icon: planeIcon(ac.heading, ac.status) }).addTo(map)
        .bindPopup(`<b>${ac.icao}</b><br>${ac.callsign||''}<br>${ac.altitude?.toFixed(0)}ft · ${ac.speed?.toFixed(0)}kt`)
    }
  }
  for (const k of Object.keys(markers)) { if (!seen.has(k)) { map.removeLayer(markers[k]); delete markers[k] } }
}

async function toggleSim() {
  simRunning.value ? await stopSim() : await startSim(15, 1000)
  refresh()
}

onMounted(() => {
  map = L.map('map', { center: [32, 108], zoom: 7 })
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 18 }).addTo(map)
  refresh()
  timer = window.setInterval(refresh, 2000)
})

onUnmounted(() => { clearInterval(timer); map?.remove() })
</script>
