<template>
  <div style="padding:16px;height:100%;overflow-y:auto;">
    <h2 style="color:#e0e0e0;margin-bottom:12px;">数据可视化</h2>
    <div ref="chart" style="width:100%;height:400px;"></div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { fetchStats } from '@/api'
import * as echarts from 'echarts'

const chart = ref<HTMLDivElement>()
let instance: echarts.ECharts, timer: number

async function refresh() {
  const st = await fetchStats()
  instance?.setOption({
    tooltip: { trigger: 'item' },
    series: [{
      type: 'pie', radius: ['40%','70%'],
      data: [
        { value: st.normalAircraft, name: '正常', itemStyle: { color: '#0f9' } },
        { value: st.abnormalAircraft, name: '异常', itemStyle: { color: '#e94560' } }
      ],
      label: { color: '#e0e0e0', fontSize: 14 }
    }]
  })
}

onMounted(() => {
  instance = echarts.init(chart.value!)
  refresh()
  timer = window.setInterval(refresh, 3000)
})

onUnmounted(() => { clearInterval(timer); instance?.dispose() })
</script>
