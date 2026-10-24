import axios from 'axios'

const api = axios.create({ baseURL: '/api' })

// 破浏览器缓存：所有 GET 请求自动加时间戳
api.interceptors.request.use(config => {
  if (config.method === 'get' || !config.method) {
    config.params = { ...config.params, _t: Date.now() }
  }
  return config
})

export const fetchPositions = () => api.get('/aircraft/positions').then(r => r.data)
export const fetchAircraft = () => api.get('/aircraft').then(r => r.data)
export const fetchStats = () => api.get('/stats').then(r => r.data)
export const fetchAlerts = (level?: string) => api.get('/alerts', { params: level ? { level } : {} }).then(r => r.data)
export const fetchSimStatus = () => api.get('/simulation/status').then(r => r.data)
export const startSim = (count = 15, interval = 1000) =>
  api.post('/simulation/start', { aircraftCount: count, intervalMs: interval }).then(r => r.data)
export const stopSim = () => api.post('/simulation/stop').then(r => r.data)
export const updateConfig = (count: number) =>
  api.post('/simulation/config', { aircraftCount: count }).then(r => r.data)
export const parseMessage = (hexMsg: string) =>
  api.post('/adsb/parse', { hexMsg }).then(r => r.data)
export const fetchMessages = (icao: string) =>
  api.get(`/aircraft/${icao}/messages`).then(r => r.data)
export const triggerAttack = (attackType?: string) =>
  api.post('/simulation/attack', attackType ? { attackType } : {}).then(r => r.data)
export const fetchAlertStats = () => api.get('/alerts/stats').then(r => r.data)
export const fetchDetectionMetrics = () => api.get('/stats/detection').then(r => r.data)

// ====== 地面站状态 + 信标机 ======
export const fetchStationStatus = () => api.get('/station/status').then(r => r.data)
export const fetchBeacon = () => api.get('/beacon').then(r => r.data)
export const setBeaconSignal = (strength: number) =>
  api.post('/beacon/signal', { strength }).then(r => r.data)

// ====== 真实 ADS-B 数据源 ======
export const importRealData = () => api.post('/realtime/import').then(r => r.data)
export const clearRealData = () => api.delete('/realtime').then(r => r.data)
export const resolveAlert = (id: number) => api.put(`/alerts/${id}/resolve`).then(r => r.data)
export const clearAlerts = () => api.delete('/alerts').then(r => r.data)
export const updateAircraftStatus = (icao: string, status: string) =>
  api.put(`/aircraft/${icao}/status`, { status }).then(r => r.data)
