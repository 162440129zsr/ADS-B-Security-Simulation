<template>
  <div class="dash">
    <!-- ====== 顶部状态栏 ====== -->
    <header class="topbar">
      <span class="logo">✈ ADS-B 飞行器监视</span>
      <div class="stat-group">
        <div class="stat"><span class="stv">{{ statOnline }}</span><span class="stl">在线</span></div>
        <div class="stat"><span class="stv">{{ statMessages }}</span><span class="stl">报文</span></div>
        <div class="stat"><span class="stv stv-bad">{{ statAbnormal }}</span><span class="stl">异常</span></div>
      </div>
      <span v-if="focusedIcao" class="focus-badge">📍 {{ focusedIcao }}</span>
      <el-button size="small" :type="simRunning?'danger':'success'" @click="toggleSim" style="margin-left:auto;">
        {{ simRunning ? '⏹ 停止' : '▶ 启动' }}
      </el-button>
    </header>

    <!-- ====== 地图 + 信息面板 ====== -->
    <div class="map-area">
      <div id="map"></div>

      <!-- 选中飞机信息面板 -->
      <transition name="slide">
        <div v-if="selectedPlane" class="info-panel">
          <div class="panel-hd">
            <div>
              <h3>{{ selectedPlane.icao }}</h3>
              <span class="callsign">{{ selectedPlane.callsign || '—' }}</span>
              <el-tag :type="selectedPlane.status==='异常'?'danger':'success'" size="small" style="margin-left:6px;">
                {{ selectedPlane.status }}
              </el-tag>
            </div>
            <el-button circle size="small" @click="selectedPlane=null;msgs=[]">✕</el-button>
          </div>
          <div class="panel-bd">
            <div class="pv"><label>位置</label><span>{{ selectedPlane.latitude?.toFixed(4) }}, {{ selectedPlane.longitude?.toFixed(4) }}</span></div>
            <div class="pv"><label>高度</label><span>{{ selectedPlane.altitude?.toFixed(0) }} ft</span></div>
            <div class="pv"><label>速度</label><span>{{ selectedPlane.speed?.toFixed(0) }} kt</span></div>
            <div class="pv"><label>航向</label><span>{{ selectedPlane.heading?.toFixed(0) }}°</span></div>
            <div class="pv"><label>类别</label><span>{{ selectedPlane.category || '—' }}</span></div>
            <el-divider style="margin:8px 0;border-color:#0f3460;"/>
            <p style="font-size:11px;color:#8b949e;margin-bottom:4px;">近期报文（最近 {{ msgs.length }} 条）</p>
            <div v-if="msgs.length===0" style="color:#666;font-size:11px;">暂无</div>
            <div v-for="m in msgs" :key="m.id" class="msg-row">
              <span class="mt">{{ fmtTs(m.receivedAt) }}</span>
              <code>{{ m.rawHex }}</code>
            </div>
          </div>
        </div>
      </transition>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchPositions, fetchStats, fetchMessages, startSim, stopSim } from '@/api'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

const route = useRoute()
const router = useRouter()

const statOnline = ref(0)
const statMessages = ref(0)
const statAbnormal = ref(0)
const simRunning = ref(false)
const selectedPlane = ref<any>(null)
const msgs = ref<any[]>([])
const focusedIcao = ref('')

let map: L.Map, timer = 0
const markers: Record<string, L.Marker> = {}
const trails: Record<string, L.Polyline> = {}
const history: Record<string, [number, number][]> = {}
const MAX_TRAIL = 30

// ====== 飞机 SVG 图标（随航向旋转 + 发光效果） ======
function planeIcon(heading: number, status: string) {
  const abnormal = status === '异常'
  const mainColor = abnormal ? '#ff4757' : '#00e5a0'
  const glowColor = abnormal ? '#ff4757' : '#00e5a0'
  const h = heading || 0
  const size = 36

  const svg = `
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" width="${size}" height="${size}">
      <defs>
        <filter id="glow-${abnormal ? 'a' : 'n'}">
          <feGaussianBlur stdDeviation="1.5" result="blur"/>
          <feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>
        </filter>
        <radialGradient id="bg-${abnormal ? 'a' : 'n'}" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stop-color="${mainColor}" stop-opacity="0.35"/>
          <stop offset="100%" stop-color="${mainColor}" stop-opacity="0"/>
        </radialGradient>
      </defs>
      <!-- 光晕背景 -->
      <circle cx="24" cy="24" r="22" fill="url(#bg-${abnormal ? 'a' : 'n'})"
        class="${abnormal ? 'plane-pulse' : ''}"/>
      <!-- 外圈 -->
      <circle cx="24" cy="24" r="14" fill="none" stroke="${mainColor}" stroke-width="1.2"
        stroke-opacity="0.5" class="${abnormal ? 'plane-pulse' : ''}"/>
      <!-- 飞机机身 -->
      <g transform="rotate(${h} 24 24)" filter="url(#glow-${abnormal ? 'a' : 'n'})">
        <!-- 机身主体 -->
        <path d="M24 6 L17 36 L20 38 L24 32 L28 38 L31 36 Z"
          fill="${mainColor}" stroke="white" stroke-width="0.6" stroke-linejoin="round"/>
        <!-- 机翼 -->
        <path d="M14 30 L24 28 L34 30 L28 32 L24 30 L20 32 Z"
          fill="${mainColor}" stroke="white" stroke-width="0.5" stroke-linejoin="round"
          opacity="0.9"/>
        <!-- 尾翼 -->
        <path d="M20 36 L24 32 L28 36"
          fill="white" opacity="0.7"/>
        <!-- 机头高光 -->
        <circle cx="24" cy="10" r="2.5" fill="white" opacity="0.6"/>
      </g>
    </svg>`

  return L.divIcon({
    html: svg,
    className: `plane-icon${abnormal ? ' plane-abnormal' : ''}`,
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2],
  })
}

function createPlaneMarker(ll: [number, number], heading: number, status: string) {
  return L.marker(ll, { icon: planeIcon(heading, status) }).addTo(map)
}

function updatePlaneMarker(icao: string, ll: [number, number], heading: number, status: string) {
  markers[icao]!.setLatLng(ll)
  markers[icao]!.setIcon(planeIcon(heading, status))
}

// ====== 格式化 ======
function fmtTs(ts: number) { return ts ? new Date(ts).toLocaleTimeString('zh-CN') : '' }

// ====== 核心刷新 ======
async function refresh() {
  try {
    const st = await fetchStats()
    statOnline.value = st.totalAircraft
    statMessages.value = st.totalMessages
    statAbnormal.value = st.abnormalAircraft
    simRunning.value = st.simulationRunning
  } catch (_) {}

  try {
    const pos = await fetchPositions()
    const seen = new Set<string>()
    for (const ac of pos) {
      if (!ac.latitude || !ac.longitude) continue
      seen.add(ac.icao)
      const ll: [number, number] = [ac.latitude, ac.longitude]

      // 轨迹
      if (!history[ac.icao]) history[ac.icao] = []
      const trail = history[ac.icao]!
      trail.push(ll)
      if (trail.length > MAX_TRAIL) trail.shift()

      // 标记
      if (markers[ac.icao]) {
        updatePlaneMarker(ac.icao, ll, ac.heading, ac.status)
      } else {
        const m = createPlaneMarker(ll, ac.heading, ac.status)
        markers[ac.icao] = m
        m.bindPopup(`<b>${ac.icao}</b><br>${ac.callsign||''}<br>${ac.altitude?.toFixed(0)}ft · ${ac.speed?.toFixed(0)}kt`)
        m.on('click', () => selectPlane(ac))
      }

      // 轨迹线
      if (trail.length >= 2) {
        const trailColor = ac.status === '异常' ? '#e94560' : '#0f9'
        if (trails[ac.icao]) {
          trails[ac.icao]!.setLatLngs(trail)
          trails[ac.icao]!.setStyle({ color: trailColor })
        } else {
          trails[ac.icao] = L.polyline(trail, {
            color: trailColor, weight: 1.5, opacity: 0.45, dashArray: '4 4',
          }).addTo(map)
        }
      }
    }

    // 清理
    for (const k of Object.keys(markers)) {
      if (!seen.has(k)) {
        map.removeLayer(markers[k]!); delete markers[k]
        if (trails[k]) { map.removeLayer(trails[k]); delete trails[k] }
        delete history[k]
      }
    }
  } catch (_) {}
}

// ====== 选中飞机弹窗 ======
async function selectPlane(ac: any) {
  selectedPlane.value = ac
  try { msgs.value = await fetchMessages(ac.icao) } catch (_) { msgs.value = [] }
}

// ====== 聚焦飞机（从列表页跳转） ======
function focusAircraft(icao: string) {
  focusedIcao.value = icao
  const pts = history[icao]
  if (pts && pts.length > 0) {
    const p = pts[pts.length - 1]!
    map.setView(p, 10, { animate: true })
    markers[icao]?.openPopup()
  } else {
    setTimeout(() => focusAircraft(icao), 500)
  }
}

// ====== 仿真启停 ======
async function toggleSim() {
  simRunning.value ? await stopSim() : await startSim(15, 1000)
  refresh()
}

// ====== 路由监听（列表页跳转） ======
watch(() => route.query.focus, icao => {
  if (icao && typeof icao === 'string') focusAircraft(icao)
})

onMounted(() => {
  map = L.map('map', { center: [32, 108], zoom: 6 })
  L.tileLayer('http://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
    maxZoom: 18, subdomains: ['1','2','3','4'],
  }).addTo(map)
  refresh()
  timer = window.setInterval(refresh, 3000)
  if (route.query.focus) setTimeout(() => focusAircraft(route.query.focus as string), 1200)
})

onUnmounted(() => { clearInterval(timer); map?.remove() })
</script>

<style>
.plane-icon { background:transparent !important; border:none !important; }
.plane-abnormal { z-index: 999 !important; }
@keyframes planePulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(1.3); }
}
.plane-pulse {
  animation: planePulse 1.2s ease-in-out infinite;
  transform-origin: center;
}
</style>

<style scoped>
.dash { display:flex; flex-direction:column; height:100%; }
.topbar {
  background:#16213e; padding:6px 14px; display:flex; align-items:center; gap:14px;
  border-bottom:1px solid #0f3460; flex-shrink:0;
}
.logo { font-size:15px; font-weight:bold; color:#e94560; }
.stat-group { display:flex; gap:12px; }
.stat { text-align:center; }
.stv { font-size:17px; font-weight:bold; color:#0f9; display:block; }
.stv-bad { color:#e94560; }
.stl { font-size:10px; color:#999; }
.focus-badge { font-size:11px; color:#0f9; background:rgba(15,255,153,.1); padding:2px 6px; border-radius:4px; }
.map-area { flex:1; position:relative; overflow:hidden; }
#map { width:100%; height:100%; }

/* 信息面板 */
.info-panel {
  position:absolute; top:10px; right:10px; width:290px; max-height:calc(100% - 20px);
  background:rgba(22,33,62,.96); border:1px solid #0f3460; border-radius:8px;
  color:#e0e0e0; font-size:12px; overflow-y:auto; z-index:1000;
  box-shadow:0 4px 24px rgba(0,0,0,.6);
}
.panel-hd { padding:10px 12px; border-bottom:1px solid #0f3460; display:flex; justify-content:space-between; align-items:flex-start; }
.panel-hd h3 { margin:0; font-size:15px; color:#0f9; }
.callsign { font-size:12px; color:#999; }
.panel-bd { padding:10px 12px; }
.pv { display:flex; justify-content:space-between; padding:3px 0; }
.pv label { color:#8b949e; font-size:11px; }
.pv span { color:#e0e0e0; font-weight:500; }
.msg-row { padding:2px 0; border-bottom:1px solid rgba(255,255,255,.04); }
.mt { font-size:10px; color:#8b949e; display:block; }
.msg-row code { font-size:10px; color:#c9d1d9; word-break:break-all; }

.slide-enter-active,.slide-leave-active { transition:transform .25s ease,opacity .25s ease; }
.slide-enter-from,.slide-leave-to { transform:translateX(30px);opacity:0; }
</style>
