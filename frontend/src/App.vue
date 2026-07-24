<template>
  <el-container style="height:100vh;">
    <el-aside width="200px" style="background:#16213e;overflow-y:auto;">
      <div style="padding:16px;text-align:center;font-size:15px;font-weight:bold;color:#e94560;border-bottom:1px solid #0f3460;">
        ✈ ADS-B 仿真
      </div>
      <el-menu :default-active="route.path" router background-color="#16213e" text-color="#999" active-text-color="#0f9" style="border-right:none;">
        <el-menu-item index="/"><span>🏠 系统首页</span></el-menu-item>
        <el-menu-item index="/aircraft"><span>📋 飞行器列表</span></el-menu-item>
        <el-menu-item index="/parse"><span>🔍 报文解析</span></el-menu-item>
        <el-menu-item index="/alerts"><span>⚠️ 安全告警</span></el-menu-item>
        <el-menu-item index="/control"><span>⚙️ 仿真控制</span></el-menu-item>
        <el-menu-item index="/data"><span>📊 数据可视化</span></el-menu-item>
      </el-menu>
    </el-aside>
    <el-main style="background:#1a1a2e;padding:0;overflow:hidden;">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'
const route = useRoute()
</script>

<style>
* { margin:0; padding:0; box-sizing:border-box; }
body { background:#1a1a2e; color:#e0e0e0; font-family:'Microsoft YaHei',sans-serif; }
</style>
