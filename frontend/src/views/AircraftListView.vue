<template>
  <div class="list-page">
    <!-- 统计摘要栏 -->
    <div class="summary-bar">
      <div class="sum-item">
        <span class="sum-val">{{ list.length }}</span>
        <span class="sum-lbl">总飞行器</span>
      </div>
      <div class="sum-item">
        <span class="sum-val green">{{ normalCount }}</span>
        <span class="sum-lbl">正常</span>
      </div>
      <div class="sum-item">
        <span class="sum-val red">{{ abnormalCount }}</span>
        <span class="sum-lbl">异常</span>
      </div>
    </div>

    <!-- 搜索筛选栏 -->
    <div class="toolbar">
      <h2 style="color:#e0e0e0;margin:0;">飞行器列表</h2>
      <div style="flex:1;"></div>
      <el-input v-model="search" placeholder="搜索 ICAO / 呼号…" clearable size="small" style="width:220px;"/>
      <el-select v-model="statusFilter" placeholder="状态筛选" clearable size="small" style="width:120px;">
        <el-option label="正常" value="正常"/>
        <el-option label="异常" value="异常"/>
      </el-select>
      <el-tooltip content="在地图中定位选中飞机">
        <el-button size="small" :disabled="!selected" @click="locateOnMap" type="primary" plain>
          🗺 地图定位
        </el-button>
      </el-tooltip>
    </div>

    <!-- 表格 -->
    <el-table
      :data="filteredList" style="width:100%;" size="small"
      @row-click="showDetail"
      highlight-current-row
      :row-class-name="rowClass"
    >
      <el-table-column prop="icao" label="ICAO" width="100" />
      <el-table-column prop="callsign" label="呼号" width="100">
        <template #default="{row}">
          <span v-if="row.callsign" style="color:#0f9;">{{ row.callsign }}</span>
          <span v-else style="color:#666;">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="类别" width="140" />
      <el-table-column label="状态" width="80">
        <template #default="{row}">
          <el-tag :type="row.status==='正常'?'success':'danger'" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="纬度" sortable sort-by="latitude">
        <template #default="{row}">{{ row.latitude?.toFixed(4) }}</template>
      </el-table-column>
      <el-table-column label="经度" sortable sort-by="longitude">
        <template #default="{row}">{{ row.longitude?.toFixed(4) }}</template>
      </el-table-column>
      <el-table-column label="高度(ft)" sortable sort-by="altitude">
        <template #default="{row}">{{ row.altitude?.toFixed(0) }}</template>
      </el-table-column>
      <el-table-column label="速度(kt)" sortable sort-by="speed">
        <template #default="{row}">{{ row.speed?.toFixed(0) }}</template>
      </el-table-column>
      <el-table-column label="航向°" sortable sort-by="heading">
        <template #default="{row}">{{ row.heading?.toFixed(0) }}</template>
      </el-table-column>
    </el-table>

    <!-- 详情弹窗 -->
    <el-dialog v-model="dialogVisible" :title="selected?.icao + ' 飞行器详情'" width="560px" :close-on-click-modal="false">
      <template v-if="selected">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="ICAO">{{ selected.icao }}</el-descriptions-item>
          <el-descriptions-item label="呼号">{{ selected.callsign || '—' }}</el-descriptions-item>
          <el-descriptions-item label="类别">{{ selected.category || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="selected.status==='异常'?'danger':'success'" size="small">{{ selected.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="纬度">{{ selected.latitude?.toFixed(4) }}</el-descriptions-item>
          <el-descriptions-item label="经度">{{ selected.longitude?.toFixed(4) }}</el-descriptions-item>
          <el-descriptions-item label="高度">{{ selected.altitude?.toFixed(0) }} ft</el-descriptions-item>
          <el-descriptions-item label="速度">{{ selected.speed?.toFixed(0) }} kt</el-descriptions-item>
          <el-descriptions-item label="航向">{{ selected.heading?.toFixed(0) }}°</el-descriptions-item>
          <el-descriptions-item label="最后更新">{{ selected.lastSeenAt || '—' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 近期报文 -->
        <el-divider/>
        <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:4px;">
          <span style="font-size:13px;color:#e0e0e0;">近期报文（最近 {{ messages.length }} 条）</span>
          <div style="display:flex;gap:6px;">
            <el-button size="small" @click="resetStatus" type="warning" plain :loading="resetting">
              🔄 重置为正常
            </el-button>
            <el-button size="small" @click="locateOnMap" type="primary" plain>🗺 在地图中定位</el-button>
          </div>
        </div>
        <div v-if="messages.length===0" style="color:#666;font-size:12px;padding:8px;">暂无报文记录</div>
        <div v-else style="max-height:200px;overflow-y:auto;">
          <div v-for="m in messages" :key="m.id" class="msg-item">
            <span class="msg-meta">{{ m.messageType || '未知' }}</span>
            <code>{{ m.rawHex }}</code>
          </div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { fetchAircraft, fetchMessages, updateAircraftStatus } from '@/api'
import { ElMessage } from 'element-plus'

const router = useRouter()
const list = ref<any[]>([])
const search = ref('')
const statusFilter = ref('')
const selected = ref<any>(null)
const messages = ref<any[]>([])
const dialogVisible = ref(false)
const resetting = ref(false)
let timer: number

const normalCount = computed(() => list.value.filter(a => a.status === '正常').length)
const abnormalCount = computed(() => list.value.filter(a => a.status === '异常').length)

const filteredList = computed(() => list.value.filter(a =>
  (!search.value || a.icao?.toUpperCase().includes(search.value.toUpperCase()) ||
                    a.callsign?.toUpperCase().includes(search.value.toUpperCase())) &&
  (!statusFilter.value || a.status === statusFilter.value)
))

function rowClass({ row }: any) {
  return row.status === '异常' ? 'row-abnormal' : ''
}

async function showDetail(row: any) {
  selected.value = row
  dialogVisible.value = true
  try { messages.value = await fetchMessages(row.icao) } catch (_) { messages.value = [] }
}

function locateOnMap() {
  if (!selected.value) return
  dialogVisible.value = false
  router.push({ path: '/', query: { focus: selected.value.icao } })
}

async function resetStatus() {
  if (!selected.value) return
  resetting.value = true
  try {
    await updateAircraftStatus(selected.value.icao, '正常')
    selected.value.status = '正常'
    ElMessage.success(`${selected.value.icao} 状态已重置为正常`)
    load()
  } catch (_) {
    ElMessage.error('重置失败')
  }
  resetting.value = false
}

async function load() {
  try { list.value = await fetchAircraft() } catch (_) { /* */ }
}

onMounted(() => { load(); timer = window.setInterval(load, 3000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.list-page { padding: 16px; height: 100%; overflow-y: auto; display: flex; flex-direction: column; }

.summary-bar { display: flex; gap: 16px; margin-bottom: 12px; }
.sum-item {
  background: #16213e; border: 1px solid #0f3460; border-radius: 6px;
  padding: 10px 20px; text-align: center; min-width: 100px;
}
.sum-val { font-size: 24px; font-weight: bold; color: #c9d1d9; display: block; }
.sum-val.green { color: #0f9; }
.sum-val.red { color: #e94560; }
.sum-lbl { font-size: 11px; color: #8b949e; }

.toolbar {
  display: flex; align-items: center; gap: 10px; margin-bottom: 10px;
}

.msg-item {
  padding: 4px 0; border-bottom: 1px solid #1a1a2e;
}
.msg-meta {
  font-size: 10px; color: #0f9; display: block;
}
.msg-item code {
  font-size: 10px; color: #c9d1d9; word-break: break-all;
}
</style>

<style>
.row-abnormal { background: rgba(233, 69, 96, 0.06) !important; }
</style>
