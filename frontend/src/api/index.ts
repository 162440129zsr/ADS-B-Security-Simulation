import axios from 'axios'

const api = axios.create({ baseURL: '/api' })

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
