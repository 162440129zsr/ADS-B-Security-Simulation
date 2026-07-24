<template>
  <div style="padding:16px;max-width:700px;">
    <h2 style="color:#e0e0e0;margin-bottom:12px;">ADS-B 报文解析</h2>
    <el-input v-model="hex" placeholder="输入 28 位十六进制报文..." maxlength="28" size="large">
      <template #append><el-button @click="parse" :loading="loading">解析</el-button></template>
    </el-input>
    <el-button size="small" @click="hex='8D4840D6202CC371C32CE0576098'" style="margin:8px 0;">📝 填充飞机识别示例</el-button>
    <el-button size="small" @click="hex='8D40621D58C382D690C8AC2863A7'" style="margin:8px 0;">📝 填充位置偶帧示例</el-button>

    <el-card v-if="result" style="background:#16213e;border-color:#0f3460;margin-top:12px;">
      <div v-for="(v,k) in result" :key="k" style="font-size:13px;line-height:1.8;">
        <span style="color:#0f9;font-weight:bold;">{{ k }}：</span>
        <span>{{ v }}</span>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { parseMessage } from '@/api'
const hex = ref('')
const result = ref<any>(null)
const loading = ref(false)
async function parse() {
  if (hex.value.length !== 28) return
  loading.value = true
  result.value = await parseMessage(hex.value)
  loading.value = false
}
</script>
