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

      <!-- 测量/显示工具条 -->
      <div class="tool-panel">
        <button class="tool-btn" :class="{active:ranging}" @click="toggleRanging">📏 测距</button>
        <button class="tool-btn" :class="{active:showRings}" @click="toggleRings">◎ 距离环</button>
        <button class="tool-btn" :class="{active:showVectors}" @click="toggleVectors">➤ 矢量线</button>
        <button class="tool-btn" :class="{active:showFilter}" @click="toggleFilter">🔍 过滤</button>
      </div>

      <!-- 测距结果 -->
      <div v-if="rangeDist" class="range-result">{{ rangeDist }}</div>

      <!-- 目标过滤器面板 -->
      <div v-if="showFilter" class="filter-panel">
        <div class="fp-title">目标过滤器</div>
        <div class="fp-row"><label>高度下限 (ft)</label>
          <el-input-number v-model="filterAltMin" :min="0" :max="60000" :step="1000" size="small" style="width:110px;"/>
        </div>
        <div class="fp-row"><label>高度上限 (ft)</label>
          <el-input-number v-model="filterAltMax" :min="0" :max="60000" :step="1000" size="small" style="width:110px;"/>
        </div>
        <div class="fp-row"><label>距离范围 (km)</label>
          <el-input-number v-model="filterMaxDist" :min="0" :max="3000" :step="50" size="small" style="width:110px;"/>
        </div>
        <div class="fp-row"><label>呼号 / ICAO</label>
          <el-input v-model="filterCallsign" size="small" placeholder="如 CCA 或 EFC4" clearable style="width:110px;"/>
        </div>
        <el-button size="small" type="danger" plain @click="resetFilter" style="width:100%;margin-top:6px;">重置过滤</el-button>
      </div>

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
const sealedTrails: Record<string, L.Polyline[]> = {}  // 已封存的历史轨迹段（断裂后冻结）
const activeLine: Record<string, L.Polyline> = {}      // 当前活动轨迹段
const history: Record<string, [number, number][]> = {} // 当前活动段点位
const lastStatus: Record<string, string> = {}
const MAX_TRAIL = 30
const BREAK_DIST_KM = 80  // 相邻点位移超过此值视为轨迹断裂

// ====== 测量 / 显示 / 过滤 状态 ======
const ranging = ref(false)
const showRings = ref(false)
const showVectors = ref(false)
const showFilter = ref(false)
const rangeDist = ref('')
const filterAltMin = ref(0)
const filterAltMax = ref(60000)
const filterMaxDist = ref(3000)
const filterCallsign = ref('')

const STATION: [number, number] = [32, 108]   // 地面站/监控终端位置（距离环+距离过滤中心）
let rings: L.Circle[] = []
let vectorLines: Record<string, L.Polyline> = {}
let rangePts: [number, number][] = []
let rangeLine: L.Polyline | null = null
let rangeMarks: L.CircleMarker[] = []

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

// 两经纬度点间距离（km），用于判断轨迹是否断裂
function distKm(a: [number, number], b: [number, number]) {
  const R = 6371
  const dLat = (b[0] - a[0]) * Math.PI / 180
  const dLon = (b[1] - a[1]) * Math.PI / 180
  const s = Math.sin(dLat / 2) ** 2
    + Math.cos(a[0] * Math.PI / 180) * Math.cos(b[0] * Math.PI / 180) * Math.sin(dLon / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(s))
}

// 封存当前轨迹段（保留在地图上冻结），为新段腾出空间
function sealSegment(icao: string, seg: [number, number][]) {
  if (seg.length >= 2 && activeLine[icao]) {
    if (!sealedTrails[icao]) sealedTrails[icao] = []
    sealedTrails[icao]!.push(activeLine[icao]!)
  }
  delete activeLine[icao]
}

// ====== 测距 ======
function toggleRanging() {
  ranging.value = !ranging.value
  rangePts = []
  rangeDist.value = ''
  if (rangeLine) { map.removeLayer(rangeLine); rangeLine = null }
  rangeMarks.forEach(m => map.removeLayer(m)); rangeMarks = []
}
function onMapClick(e: L.LeafletMouseEvent) {
  if (!ranging.value) return
  rangePts.push([e.latlng.lat, e.latlng.lng])
  rangeMarks.push(L.circleMarker(e.latlng, { radius: 5, color: '#f0c040', weight: 2 }).addTo(map))
  if (rangePts.length === 2) {
    const d = distKm(rangePts[0]!, rangePts[1]!)
    rangeDist.value = `两点距离：${d.toFixed(1)} km`
    if (rangeLine) map.removeLayer(rangeLine)
    rangeLine = L.polyline(rangePts, { color: '#f0c040', weight: 2 }).addTo(map)
    rangePts = []
  }
}

// ====== 距离环 ======
function toggleRings() {
  showRings.value = !showRings.value
  showRings.value ? drawRings() : clearRings()
}
function drawRings() {
  clearRings()
  ;[100, 200, 400].forEach(r => {
    const c = L.circle(STATION, { radius: r * 1000, color: '#8ab4f8', weight: 1, opacity: 0.5, fill: false, dashArray: '4 4' })
      .bindTooltip(`${r} km`, { permanent: true, direction: 'top', className: 'ring-label' })
      .addTo(map)
    rings.push(c)
  })
}
function clearRings() { rings.forEach(c => map.removeLayer(c)); rings = [] }

// ====== 航迹速度矢量线（预测未来位置） ======
function toggleVectors() {
  showVectors.value = !showVectors.value
  if (!showVectors.value) clearVectors()
}
function clearVectors() {
  Object.values(vectorLines).forEach(p => map.removeLayer(p))
  vectorLines = {}
}
function predictPosition(lat: number, lon: number, speedKt: number, heading: number, seconds: number): [number, number] {
  const distKm = (speedKt || 0) * 1.852 * (seconds / 3600)
  const br = (heading || 0) * Math.PI / 180
  const dLat = distKm * Math.cos(br) / 111.32
  const dLon = distKm * Math.sin(br) / (111.32 * Math.cos(lat * Math.PI / 180))
  return [lat + dLat, lon + dLon]
}
function updateVectors(ac: any, ll: [number, number]) {
  if (!showVectors.value) return
  const [lat2, lon2] = predictPosition(ll[0], ll[1], ac.speed || 0, ac.heading || 0, 120)
  const color = ac.status === '异常' ? '#e94560' : '#f0c040'
  if (vectorLines[ac.icao]) {
    vectorLines[ac.icao]!.setLatLngs([[ll[0], ll[1]], [lat2, lon2]])
    vectorLines[ac.icao]!.setStyle({ color })
  } else {
    vectorLines[ac.icao] = L.polyline([[ll[0], ll[1]], [lat2, lon2]], { color, weight: 1.5, opacity: 0.7, dashArray: '3 6' }).addTo(map)
  }
}

// ====== 目标过滤器 ======
function toggleFilter() { showFilter.value = !showFilter.value }
function resetFilter() {
  filterAltMin.value = 0; filterAltMax.value = 60000
  filterMaxDist.value = 3000; filterCallsign.value = ''
}
function applyFilter(ac: any): boolean {
  const alt = ac.altitude || 0
  if (alt < filterAltMin.value || alt > filterAltMax.value) return false
  if (filterMaxDist.value > 0 && ac.latitude && ac.longitude) {
    const d = distKm(STATION, [ac.latitude, ac.longitude])
    if (d > filterMaxDist.value) return false
  }
  if (filterCallsign.value) {
    const q = filterCallsign.value.toUpperCase()
    const icao = (ac.icao || '').toUpperCase()
    const cs = (ac.callsign || '').toUpperCase()
    if (!icao.includes(q) && !cs.includes(q)) return false
  }
  return true
}

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
      if (!applyFilter(ac)) continue   // 目标过滤器
      seen.add(ac.icao)
      const ll: [number, number] = [ac.latitude, ac.longitude]

      // 轨迹（支持断裂：状态翻转或位置跳变时封存旧段、开新段）
      const status = ac.status
      const prevStatus = lastStatus[ac.icao]
      if (!history[ac.icao]) history[ac.icao] = []
      let seg = history[ac.icao]!
      const jumped = seg.length > 0 && distKm(seg[seg.length - 1]!, ll) > BREAK_DIST_KM
      const flipped = prevStatus != null && prevStatus !== status
      if (jumped || flipped) {
        sealSegment(ac.icao, seg)
        history[ac.icao] = []
        seg = history[ac.icao]!
      }
      lastStatus[ac.icao] = status
      seg.push(ll)
      if (seg.length > MAX_TRAIL) seg.shift()

      // 标记
      if (markers[ac.icao]) {
        updatePlaneMarker(ac.icao, ll, ac.heading, status)
      } else {
        const m = createPlaneMarker(ll, ac.heading, status)
        markers[ac.icao] = m
        m.bindPopup(`<b>${ac.icao}</b><br>${ac.callsign||''}<br>${ac.altitude?.toFixed(0)}ft · ${ac.speed?.toFixed(0)}kt`)
        m.on('click', () => selectPlane(ac))
      }

      // 轨迹线
      if (seg.length >= 2) {
        const trailColor = status === '异常' ? '#e94560' : '#0f9'
        if (activeLine[ac.icao]) {
          activeLine[ac.icao]!.setLatLngs(seg)
          activeLine[ac.icao]!.setStyle({ color: trailColor })
        } else {
          activeLine[ac.icao] = L.polyline(seg, {
            color: trailColor, weight: 1.5, opacity: 0.45, dashArray: '4 4',
          }).addTo(map)
        }
      }

      // 航迹速度矢量线
      updateVectors(ac, ll)
    }

    // 清理
    for (const k of Object.keys(markers)) {
      if (!seen.has(k)) {
        map.removeLayer(markers[k]!); delete markers[k]
        if (activeLine[k]) { map.removeLayer(activeLine[k]!); delete activeLine[k] }
        if (sealedTrails[k]) { sealedTrails[k]!.forEach(p => map.removeLayer(p)); delete sealedTrails[k] }
        if (vectorLines[k]) { map.removeLayer(vectorLines[k]!); delete vectorLines[k] }
        delete history[k]
        delete lastStatus[k]
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
  map.on('click', onMapClick)
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

/* 测量/显示工具条 */
.tool-panel {
  position:absolute; left:10px; top:10px; z-index:1000;
  display:flex; flex-direction:column; gap:6px;
}
.tool-btn {
  background:rgba(22,33,62,.92); color:#c9d1d9; border:1px solid #0f3460;
  border-radius:6px; padding:6px 10px; font-size:12px; cursor:pointer;
  text-align:left; width:88px;
}
.tool-btn:hover { border-color:#0f9; color:#0f9; }
.tool-btn.active { border-color:#0f9; color:#0f9; background:rgba(15,255,153,.08); }

.range-result {
  position:absolute; left:104px; top:10px; z-index:1000;
  background:rgba(22,33,62,.92); color:#f0c040; border:1px solid #0f3460;
  border-radius:6px; padding:6px 10px; font-size:12px;
}

/* 目标过滤器面板 */
.filter-panel {
  position:absolute; left:10px; top:150px; z-index:1000; width:200px;
  background:rgba(22,33,62,.96); border:1px solid #0f3460; border-radius:8px;
  padding:10px; color:#e0e0e0; font-size:12px;
  box-shadow:0 4px 24px rgba(0,0,0,.6);
}
.fp-title { font-size:13px; font-weight:bold; color:#0f9; margin-bottom:8px; }
.fp-row { display:flex; align-items:center; justify-content:space-between; margin-bottom:6px; }
.fp-row label { color:#8b949e; font-size:11px; }
</style>
