package com.adsb.simulation.controller;

import com.adsb.simulation.service.StationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 地面站状态 + 信标机接口
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StationController {

    private final StationService stationService;

    /** 站点状态报告（CAT023 风格） */
    @GetMapping("/station/status")
    public Map<String, Object> stationStatus() {
        return stationService.getStationStatus();
    }

    /** 信标机状态 */
    @GetMapping("/beacon")
    public Object beacon() {
        return stationService.getBeacon();
    }

    /** 设置信标信号强度（模拟天馈性能下降/恢复） */
    @PostMapping("/beacon/signal")
    public Map<String, Object> setBeaconSignal(@RequestBody Map<String, Object> body) {
        double strength = body.containsKey("strength")
                ? ((Number) body.get("strength")).doubleValue()
                : 85.0;
        return stationService.setBeaconStrength(strength);
    }
}
