<template>
  <div style="padding:16px;height:100%;overflow-y:auto;">
    <div style="display:flex;align-items:center;gap:12px;margin-bottom:12px;">
      <h2 style="color:#e0e0e0;">飞行器列表</h2>
      <el-input v-model="search" placeholder="搜索 ICAO..." clearable style="width:200px;" size="small"/>
      <el-select v-model="statusFilter" placeholder="状态筛选" clearable size="small" style="width:120px;">
        <el-option label="正常" value="正常"/>
        <el-option label="异常" value="异常"/>
      </el-select>
    </div>
    <el-table :data="filteredList" style="width:100%;" size="small" @row-click="showDetail" stripe>
      <el-table-column prop="icao" label="ICAO" width="100"/>
      <el-table-column prop="callsign" label="呼号" width="100"/>
      <el-table-column label="状态" width="80">
        <template #default="{row}">
          <el-tag :type="row.status==='正常'?'success':'danger'" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="纬度"><template #default="{row}">{{ row.latitude?.toFixed(4) }}</template></el-table-column>
      <el-table-column label="经度"><template #default="{row}">{{ row.longitude?.toFixed(4) }}</template></el-table-column>
      <el-table-column label="高度(ft)"><template #default="{row}">{{ row.altitude?.toFixed(0) }}</template></el-table-column>
      <el-table-column label="速度(kt)"><template #default="{row}">{{ row.speed?.toFixed(0) }}</template></el-table-column>
      <el-table-column label="航向°"><template #default="{row}">{{ row.heading?.toFixed(0) }}</template></el-table-column>
    </el-table>

    <!-- 详情弹窗 -->
    <el-dialog v-model="dialogVisible" :title="selected?.icao + ' 详情'" width="500px">
      <template v-if="selected">
        <p><b>ICAO：</b>{{ selected.icao }}</p>
        <p><b>呼号：</b>{{ selected.callsign || '—' }}</p>
        <p><b>类别：</b>{{ selected.category || '—' }}</p>
        <p><b>位置：</b>{{ selected.latitude?.toFixed(4) }}, {{ selected.longitude?.toFixed(4) }}</p>
        <p><b>高度：</b>{{ selected.altitude?.toFixed(0) }} ft</p>
        <p><b>速度：</b>{{ selected.speed?.toFixed(0) }} kt</p>
        <p><b>航向：</b>{{ selected.heading?.toFixed(0) }}°</p>
        <p><b>状态：</b>{{ selected.status }}</p>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { fetchAircraft } from '@/api'

const list = ref<any[]>([])
const search = ref('')
const statusFilter = ref('')
const selected = ref<any>(null)
const dialogVisible = ref(false)
let timer: number

const filteredList = computed(() => list.value.filter(a =>
  (!search.value || a.icao?.includes(search.value.toUpperCase())) &&
  (!statusFilter.value || a.status === statusFilter.value)
))

function showDetail(row: any) { selected.value = row; dialogVisible.value = true }

async function load() { list.value = await fetchAircraft() }

onMounted(() => { load(); timer = window.setInterval(load, 2000) })
onUnmounted(() => clearInterval(timer))
</script>
