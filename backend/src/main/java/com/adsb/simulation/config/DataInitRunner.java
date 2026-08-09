package com.adsb.simulation.config;

import com.adsb.simulation.repository.AircraftRepository;
import com.adsb.simulation.service.SimulationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动后自动初始化种子数据并启动仿真
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitRunner implements CommandLineRunner {

    private final SimulationService simulationService;
    private final AircraftRepository aircraftRepo;

    @Override
    public void run(String... args) {
        long count = aircraftRepo.count();
        if (count == 0) {
            log.info("数据库为空，初始化种子数据...");
            simulationService.seedAircraft(15);
        } else {
            log.info("数据库已有 {} 架飞机，跳过种子初始化", count);
        }
        simulationService.start(15, 1000);
        log.info("仿真已启动：15 架飞机，1 秒刷新");
    }
}
