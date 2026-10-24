package com.adsb.simulation.controller;

import com.adsb.simulation.model.Aircraft;
import com.adsb.simulation.model.AdsbMessage;
import com.adsb.simulation.model.SecurityAlert;
import com.adsb.simulation.repository.*;
import com.adsb.simulation.service.SecurityCheckService;
import com.adsb.simulation.service.SimulationService;
import com.adsb.simulation.service.AttackInjector;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AircraftController {

    private final AircraftRepository aircraftRepo;
    private final AdsbMessageRepository messageRepo;
    private final SecurityAlertRepository alertRepo;
    private final SimulationService simulationService;
    private final SecurityCheckService securityCheckService;
    private final AttackInjector attackInjector;

    // ====== 飞行器 ======

    @GetMapping("/aircraft")
    public List<Aircraft> listAircraft(@RequestParam(required = false) String status) {
        if (status != null) return aircraftRepo.findByStatus(status);
        return aircraftRepo.findAll();
    }

    @GetMapping("/aircraft/{icao}")
    public Aircraft getAircraft(@PathVariable String icao) {
        return aircraftRepo.findByIcao(icao).orElse(null);
    }

    @GetMapping("/aircraft/positions")
    public List<Map<String, Object>> getPositions() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Aircraft ac : aircraftRepo.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("icao", ac.getIcao());
            m.put("callsign", ac.getCallsign());
            m.put("latitude", ac.getLatitude());
            m.put("longitude", ac.getLongitude());
            m.put("altitude", ac.getAltitude());
            m.put("speed", ac.getSpeed());
            m.put("heading", ac.getHeading());
            m.put("status", ac.getStatus());
            m.put("lastSeenAt", ac.getLastSeenAt());
            list.add(m);
        }
        return list;
    }

    @GetMapping("/aircraft/{icao}/messages")
    public List<AdsbMessage> getMessages(@PathVariable String icao,
                                          @RequestParam(defaultValue = "20") int limit) {
        List<AdsbMessage> msgs = messageRepo.findByIcaoOrderByReceivedAtDesc(icao);
        return msgs.subList(0, Math.min(limit, msgs.size()));
    }

    // ====== 仿真控制 ======

    @PostMapping("/simulation/start")
    public Map<String, Object> startSimulation(@RequestBody Map<String, Object> body) {
        int count = body.containsKey("aircraftCount")
                ? ((Number) body.get("aircraftCount")).intValue() : 15;
        int interval = body.containsKey("intervalMs")
                ? ((Number) body.get("intervalMs")).intValue() : 1000;
        simulationService.start(count, interval);
        return Map.of("status", "started", "aircraftCount", count);
    }

    @PostMapping("/simulation/stop")
    public Map<String, String> stopSimulation() {
        simulationService.stop();
        return Map.of("status", "stopped");
    }

    @GetMapping("/simulation/status")
    public Map<String, Object> simulationStatus() {
        return Map.of("running", simulationService.isRunning(),
                "aircraftCount", aircraftRepo.count(),
                "messageCount", messageRepo.count());
    }

    /** 运行时调整飞机数量 */
    @PostMapping("/simulation/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> body) {
        if (body.containsKey("aircraftCount")) {
            int count = ((Number) body.get("aircraftCount")).intValue();
            simulationService.setAircraftCount(count);
        }
        return Map.of("success", true, "aircraftCount", aircraftRepo.count());
    }

    /** 攻击模拟：通过独立注入通道写入恶意报文（source=INJECTED），触发纯报文检测 */
    @PostMapping("/simulation/attack")
    public Map<String, Object> triggerAttack(@RequestBody(required = false) Map<String, Object> body) {
        String attackType = body != null && body.containsKey("attackType")
                ? (String) body.get("attackType") : null;
        return attackInjector.inject(attackType);
    }

    // ====== 告警 ======

    @GetMapping("/alerts")
    public List<SecurityAlert> listAlerts(@RequestParam(required = false) String level) {
        if (level != null) return alertRepo.findByAlertLevel(level);
        return alertRepo.findByIsResolvedFalseOrderByCreatedAtDesc();
    }

    @GetMapping("/alerts/stats")
    public Map<String, Object> alertStats() {
        return securityCheckService.getAlertStats();
    }

    @PutMapping("/alerts/{id}/resolve")
    public Map<String, Object> resolveAlert(@PathVariable Long id) {
        SecurityAlert alert = alertRepo.findById(id).orElse(null);
        if (alert != null) {
            alert.setIsResolved(true);
            alertRepo.save(alert);
            securityCheckService.clearDedupCache(alert.getIcao());
            return Map.of("success", true, "id", id);
        }
        return Map.of("success", false, "message", "告警不存在");
    }

    @DeleteMapping("/alerts")
    public Map<String, Object> clearAlerts() {
        long count = alertRepo.count();
        alertRepo.deleteAll();
        // 清除所有去重缓存
        securityCheckService.clearAllDedupCache();
        // 同步重置所有飞机状态为正常
        int resetCount = 0;
        for (Aircraft ac : aircraftRepo.findAll()) {
            if (!"正常".equals(ac.getStatus())) {
                ac.setStatus("正常");
                aircraftRepo.save(ac);
                resetCount++;
            }
        }
        return Map.of("success", true, "deleted", count, "aircraftReset", resetCount);
    }

    /** 手动设置飞行器状态（调试用） */
    @PutMapping("/aircraft/{icao}/status")
    public Map<String, Object> updateAircraftStatus(@PathVariable String icao,
                                                     @RequestBody Map<String, Object> body) {
        Aircraft ac = aircraftRepo.findByIcao(icao).orElse(null);
        if (ac == null) return Map.of("success", false, "message", "飞行器不存在");
        String newStatus = body.containsKey("status") ? (String) body.get("status") : "正常";
        ac.setStatus(newStatus);
        aircraftRepo.save(ac);
        // 重置该飞机的告警去重缓存
        securityCheckService.clearDedupCache(icao);
        return Map.of("success", true, "icao", icao, "status", newStatus);
    }

    // ====== 统计 ======

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
                "totalAircraft", aircraftRepo.count(),
                "normalAircraft", aircraftRepo.findByStatus("正常").size(),
                "abnormalAircraft", aircraftRepo.findByStatus("异常").size(),
                "totalMessages", messageRepo.count(),
                "totalAlerts", alertRepo.count(),
                "unresolvedAlerts", alertRepo.countByIsResolvedFalse(),
                "simulationRunning", simulationService.isRunning()
        );
    }

    /** 检测效果量化指标：检出率/误报率/精确率/F1 */
    @GetMapping("/stats/detection")
    public Map<String, Object> detectionMetrics() {
        return securityCheckService.getDetectionMetrics();
    }
}
