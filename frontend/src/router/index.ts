import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '系统首页' } },
    { path: '/aircraft', name: 'aircraft', component: () => import('@/views/AircraftListView.vue'), meta: { title: '飞行器列表' } },
    { path: '/parse', name: 'parse', component: () => import('@/views/MessageParseView.vue'), meta: { title: '报文解析' } },
    { path: '/alerts', name: 'alerts', component: () => import('@/views/SecurityAlertView.vue'), meta: { title: '安全告警' } },
    { path: '/control', name: 'control', component: () => import('@/views/SimControlView.vue'), meta: { title: '仿真控制' } },
    { path: '/data', name: 'data', component: () => import('@/views/DataView.vue'), meta: { title: '数据可视化' } }
  ]
})

export default router
