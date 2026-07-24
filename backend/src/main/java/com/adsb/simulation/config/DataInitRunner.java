package com.adsb.simulation.config;

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

    @Override
    public void run(String... args) {
        log.info("正在初始化演示数据...");
        simulationService.seedAircraft(15);
        simulationService.start(15, 1000);
        log.info("演示环境就绪：15 架飞机，1 秒刷新");
    }
}
