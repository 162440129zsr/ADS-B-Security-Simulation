package com.adsb.simulation.controller;

import com.adsb.simulation.model.Aircraft;
import com.adsb.simulation.model.AdsbMessage;
import com.adsb.simulation.model.SecurityAlert;
import com.adsb.simulation.repository.*;
import com.adsb.simulation.service.SimulationService;
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

    // ====== 告警 ======

    @GetMapping("/alerts")
    public List<SecurityAlert> listAlerts(@RequestParam(required = false) String level) {
        if (level != null) return alertRepo.findByAlertLevel(level);
        return alertRepo.findByIsResolvedFalseOrderByCreatedAtDesc();
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
}
