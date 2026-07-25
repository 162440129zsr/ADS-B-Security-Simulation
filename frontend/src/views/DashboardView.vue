<template>
  <div class="dashboard">
    <!-- 顶部状态栏 -->
    <header class="topbar">
      <span class="logo">✈ ADS-B 飞行器监视</span>
      <div class="stat-cards">
        <div class="stat-item">
          <span class="stat-val">{{ statCards.online }}</span>
          <span class="stat-lbl">在线飞行器</span>
        </div>
        <div class="stat-item">
          <span class="stat-val">{{ statCards.messages }}</span>
          <span class="stat-lbl">报文总数</span>
        </div>
        <div class="stat-item">
          <span class="stat-val danger">{{ statCards.abnormal }}</span>
          <span class="stat-lbl">异常</span>
        </div>
      </div>
      <el-button size="small" :type="simRunning ? 'danger' : 'success'" @click="toggleSim" style="margin-left:auto;">
        {{ simRunning ? '⏹ 停止仿真' : '▶ 启动仿真' }}
      </el-button>
    </header>

    <!-- 地图区域 -->
    <div class="map-wrapper">
      <div id="map" ref="mapEl"></div>

      <!-- 右侧飞机信息面板 -->
      <transition name="slide">
        <div v-if="selectedPlane" class="plane-panel">
          <div class="panel-header">
            <h3>{{ selectedPlane.icao }}</h3>
            <span class="panel-callsign">{{ selectedPlane.callsign || '—' }}</span>
            <el-button circle size="small" @click="selectedPlane=null;selectedMessages=[]" style="float:right;">
              ✕
            </el-button>
          </div>
          <div class="panel-body">
            <div class="info-row"><label>纬度</label><span>{{ selectedPlane.latitude?.toFixed(4) }}</span></div>
            <div class="info-row"><label>经度</label><span>{{ selectedPlane.longitude?.toFixed(4) }}</span></div>
            <div class="info-row"><label>高度</label><span>{{ selectedPlane.altitude?.toFixed(0) }} ft</span></div>
            <div class="info-row"><label>速度</label><span>{{ selectedPlane.speed?.toFixed(0) }} kt</span></div>
            <div class="info-row"><label>航向</label><span>{{ selectedPlane.heading?.toFixed(0) }}°</span></div>
            <div class="info-row">
              <label>状态</label>
              <el-tag :type="selectedPlane.status==='异常'?'danger':'success'" size="small">{{ selectedPlane.status }}</el-tag>
            </div>
            <el-divider style="margin:8px 0;border-color:#0f3460;"/>
            <p style="font-size:11px;color:#8b949e;margin-bottom:4px;">近期报文</p>
            <div v-if="selectedMessages.length===0" style="color:#666;font-size:11px;">暂无报文记录</div>
            <div v-for="m in selectedMessages" :key="m.id" class="msg-row">
              <span class="msg-time">{{ formatTs(m.receivedAt) }}</span>
              <code class="msg-hex">{{ m.rawHex?.substring(0, 20) }}…</code>
            </div>
          </div>
        </div>
      </transition>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchPositions, fetchStats, fetchSimStatus, fetchMessages, startSim, stopSim } from '@/api'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

const route = useRoute()
const router = useRouter()

const mapEl = ref<HTMLDivElement>()
const simRunning = ref(false)
const selectedPlane = ref<any>(null)
const selectedMessages = ref<any[]>([])

const statCards = reactive({ online: 0, messages: 0, abnormal: 0 })

let map: L.Map
let timer = 0
const markers: Record<string, L.Marker> = {}
const trails: Record<string, L.Polyline> = {}
const history: Record<string, [number, number][]> = {}
const MAX_TRAIL = 30

// ---- SVG 飞机图标 ----
function planeIcon(heading: number, status: string) {
  const color = status === '异常' ? '#e94560' : '#0f9'
  const h = heading || 0
  return L.divIcon({
    html: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="28" height="28"
      style="transform:rotate(${h}deg);filter:drop-shadow(0 1px 2px rgba(0,0,0,.4));display:block;">
      <path d="M21 16v-2l-8-5V3.5A1.5 1.5 0 0 0 11.5 2 1.5 1.5 0 0 0 10 3.5V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5z"
        fill="${color}" stroke="white" stroke-width="0.5"/>
    </svg>`,
    className: 'plane-icon',
    iconSize: [28, 28],
    iconAnchor: [14, 14],
  })
}

function formatTs(ts: number) {
  if (!ts) return ''
  return new Date(ts).toLocaleTimeString('zh-CN')
}

// ---- 核心刷新 ----
async function refresh() {
  try {
    const [pos, st, status] = await Promise.all([
      fetchPositions(), fetchStats(), fetchSimStatus()
    ])
    simRunning.value = status.running
    statCards.online = pos.length
    statCards.messages = st.totalMessages
    statCards.abnormal = st.abnormalAircraft

    const seen = new Set<string>()
    for (const ac of pos) {
      if (!ac.latitude || !ac.longitude) continue
      seen.add(ac.icao)
      const ll: [number, number] = [ac.latitude, ac.longitude]

      // 轨迹历史
      if (!history[ac.icao]) history[ac.icao] = []
      history[ac.icao].push(ll)
      if (history[ac.icao].length > MAX_TRAIL) history[ac.icao].shift()

      // Marker
      if (markers[ac.icao]) {
        markers[ac.icao].setLatLng(ll)
        markers[ac.icao].setIcon(planeIcon(ac.heading, ac.status))
      } else {
        const m = L.marker(ll, { icon: planeIcon(ac.heading, ac.status) }).addTo(map)
        m.bindPopup(`<b>${ac.icao}</b><br>${ac.callsign||''}<br>${ac.altitude?.toFixed(0)}ft · ${ac.speed?.toFixed(0)}kt`)
        m.on('click', () => selectPlane(ac))
        markers[ac.icao] = m
      }

      // 轨迹线
      if (history[ac.icao].length >= 2) {
        if (trails[ac.icao]) {
          trails[ac.icao].setLatLngs(history[ac.icao])
        } else {
          trails[ac.icao] = L.polyline(history[ac.icao], {
            color: ac.status === '异常' ? '#e94560' : '#0f9',
            weight: 1.5, opacity: 0.5, dashArray: '4 4',
          }).addTo(map)
        }
      }
    }

    // 清理消失的飞机
    for (const k of Object.keys(markers)) {
      if (!seen.has(k)) {
        map.removeLayer(markers[k]); delete markers[k]
        if (trails[k]) { map.removeLayer(trails[k]); delete trails[k] }
        delete history[k]
      }
    }
  } catch (_) { /* 网络错误静默处理 */ }
}

// ---- 选中飞机 ----
async function selectPlane(ac: any) {
  selectedPlane.value = ac
  try {
    selectedMessages.value = await fetchMessages(ac.icao)
  } catch (_) { selectedMessages.value = [] }
}

// ---- 聚焦某架飞机（从列表页跳转） ----
function focusAircraft(icao: string) {
  const posList = history[icao]
  if (posList && posList.length > 0) {
    const pos = posList[posList.length - 1]
    map.setView(pos, 10, { animate: true })
    // 打开弹窗
    if (markers[icao]) {
      markers[icao].openPopup()
    }
  }
}

// ---- 启停仿真 ----
async function toggleSim() {
  try {
    simRunning.value ? await stopSim() : await startSim(15, 1000)
    refresh()
  } catch (_) { /* */ }
}

// ---- 监听路由 ----
watch(() => route.query.focus, (icao) => {
  if (icao && typeof icao === 'string') {
    const tryFocus = () => {
      if (history[icao]) { focusAircraft(icao); router.replace({ query: {} }) }
      else { setTimeout(tryFocus, 500) }
    }
    tryFocus()
  }
})

onMounted(() => {
  map = L.map('map', { center: [32, 108], zoom: 6, zoomControl: true })
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 18,
    attribution: '&copy; OSM | ADS-B Simulation',
  }).addTo(map)

  refresh()
  timer = window.setInterval(refresh, 3000)

  // 初始 ?focus=
  if (route.query.focus) {
    setTimeout(() => focusAircraft(route.query.focus as string), 1200)
  }
})

onUnmounted(() => {
  clearInterval(timer)
  map?.remove()
})
</script>

<style scoped>
.dashboard { display: flex; flex-direction: column; height: 100%; }
.topbar {
  background: #16213e; padding: 8px 16px;
  display: flex; align-items: center; gap: 20px;
  border-bottom: 1px solid #0f3460; flex-shrink: 0;
}
.logo { font-size: 16px; font-weight: bold; color: #e94560; white-space: nowrap; }
.stat-cards { display: flex; gap: 16px; }
.stat-item { text-align: center; }
.stat-val { font-size: 18px; font-weight: bold; color: #0f9; display: block; }
.stat-val.danger { color: #e94560; }
.stat-lbl { font-size: 10px; color: #999; }
.map-wrapper { flex: 1; position: relative; overflow: hidden; }
#map { width: 100%; height: 100%; }

.plane-panel {
  position: absolute; top: 10px; right: 10px;
  width: 280px; max-height: calc(100% - 20px);
  background: rgba(22,33,62,.95); border: 1px solid #0f3460;
  border-radius: 8px; color: #e0e0e0; font-size: 12px;
  overflow-y: auto; z-index: 1000;
  box-shadow: 0 4px 20px rgba(0,0,0,.5);
}
.panel-header {
  padding: 10px 12px; border-bottom: 1px solid #0f3460;
}
.panel-header h3 { margin: 0; font-size: 15px; color: #0f9; display: inline; }
.panel-callsign { margin-left: 8px; font-size: 12px; color: #999; }
.panel-body { padding: 10px 12px; }
.info-row { display: flex; justify-content: space-between; padding: 3px 0; }
.info-row label { color: #8b949e; font-size: 11px; }
.info-row span { color: #e0e0e0; font-weight: 500; }
.msg-row { padding: 2px 0; }
.msg-time { font-size: 10px; color: #8b949e; display: block; }
.msg-hex { font-size: 10px; color: #c9d1d9; }

.slide-enter-active, .slide-leave-active { transition: transform .25s ease, opacity .25s ease; }
.slide-enter-from, .slide-leave-to { transform: translateX(30px); opacity: 0; }
</style>

<style>
.plane-icon { background: transparent !important; border: none !important; }
</style>
