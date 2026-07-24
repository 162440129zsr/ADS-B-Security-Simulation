package com.adsb.simulation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "ADS-B Simulation Backend",
                "version", "0.1.0",
                "timestamp", LocalDateTime.now().toString()
        );
    }
}
