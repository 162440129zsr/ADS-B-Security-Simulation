package com.adsb.simulation.service;

import com.adsb.simulation.model.AdsbMessage;
import com.adsb.simulation.model.Aircraft;
import com.adsb.simulation.model.SimulationConfig;
import com.adsb.simulation.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

/**
 * 仿真模拟服务 —— 正常仿真通道
 *
 * 架构改造后，本服务【只】产生正常报文（source=NORMAL），
 * 不再设置任何飞机的 status 为「异常」，也不再包含攻击逻辑。
 * 攻击注入统一走独立的 {@link AttackInjector} 通道。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulationService {

    private final AircraftRepository aircraftRepo;
    private final AdsbMessageRepository messageRepo;
    private final SecurityAlertRepository alertRepo;
    private final SimulationConfigRepository configRepo;
    private final AdsbMessageFactory messageFactory;
    private final SecurityCheckService securityCheckService;

    private static final Random RNG = new Random();

    // 中国空域范围
    private static final double LAT_MIN = 20.0, LAT_MAX = 45.0;
    private static final double LON_MIN = 75.0, LON_MAX = 130.0;
    private static final double ALT_MIN = 5000, ALT_MAX = 40000;
    private static final double SPEED_MIN = 200, SPEED_MAX = 550;

    private static final double EARTH_RADIUS = 6371000.0;

    private boolean running = false;

    public boolean isRunning() { return running; }

    /**
     * 初始化种子数据：仅在数据库为空时调用，生成 N 架飞机
     */
    public List<Aircraft> seedAircraft(int count) {
        for (int i = 0; i < count; i++) {
            Aircraft ac = Aircraft.builder()
                    .icao(randomIcao())
                    .callsign(randomCallsign())
                    .category(randomCategory())
                    .latitude(LAT_MIN + RNG.nextDouble() * (LAT_MAX - LAT_MIN))
                    .longitude(LON_MIN + RNG.nextDouble() * (LON_MAX - LON_MIN))
                    .altitude(ALT_MIN + RNG.nextDouble() * (ALT_MAX - ALT_MIN))
                    .speed(SPEED_MIN + RNG.nextDouble() * (SPEED_MAX - SPEED_MIN))
                    .heading(RNG.nextDouble() * 360)
                    .status("正常")
                    .build();
            aircraftRepo.save(ac);
        }
        log.info("已生成 {} 架飞行器种子数据", count);
        return aircraftRepo.findAll();
    }

    /**
     * 启动仿真
     */
    public void start(int aircraftCount, int intervalMs) {
        if (aircraftRepo.countBySource("SIMULATION") == 0) {
            seedAircraft(aircraftCount);
        }
        running = true;

        List<SimulationConfig> configs = configRepo.findAll();
        SimulationConfig config;
        if (configs.isEmpty()) {
            config = SimulationConfig.builder()
                    .aircraftCount(aircraftCount)
                    .messageIntervalMs(intervalMs)
                    .isRunning(true)
                    .build();
        } else {
            config = configs.get(0);
            config.setAircraftCount(aircraftCount);
            config.setMessageIntervalMs(intervalMs);
            config.setIsRunning(true);
        }
        configRepo.save(config);
        log.info("仿真已启动：{} 架飞机，间隔 {}ms", aircraftCount, intervalMs);
    }

    public void stop() {
        running = false;
        configRepo.findAll().forEach(c -> { c.setIsRunning(false); configRepo.save(c); });
        log.info("仿真已停止");
    }

    /** 运行时调整飞机数量（减少时同步清理该飞机的告警/报文/检测缓存，避免孤儿数据） */
    @Transactional
    public void setAircraftCount(int count) {
        int current = (int) aircraftRepo.countBySource("SIMULATION");
        if (count > current) {
            int add = count - current;
            for (int i = 0; i < add; i++) {
                Aircraft ac = Aircraft.builder()
                        .icao(randomIcao()).callsign(randomCallsign())
                        .category(randomCategory())
                        .latitude(LAT_MIN + RNG.nextDouble() * (LAT_MAX - LAT_MIN))
                        .longitude(LON_MIN + RNG.nextDouble() * (LON_MAX - LON_MIN))
                        .altitude(ALT_MIN + RNG.nextDouble() * (ALT_MAX - ALT_MIN))
                        .speed(SPEED_MIN + RNG.nextDouble() * (SPEED_MAX - SPEED_MIN))
                        .heading(RNG.nextDouble() * 360).status("正常")
                        .build();
                aircraftRepo.save(ac);
            }
        } else if (count < current) {
            List<Aircraft> all = aircraftRepo.findBySource("SIMULATION");
            for (int i = current - 1; i >= count; i--) {
                Aircraft ac = all.get(i);
                alertRepo.deleteByIcao(ac.getIcao());       // 清理该飞机的告警
                messageRepo.deleteByIcao(ac.getIcao());     // 清理该飞机的报文
                securityCheckService.removeAircraft(ac.getIcao()); // 清理检测缓存
                aircraftRepo.delete(ac);
            }
        }
        log.info("飞机数量调整：{} → {}", current, count);
    }

    /** 获取当前配置 */
    public SimulationConfig getConfig() {
        List<SimulationConfig> configs = configRepo.findAll();
        return configs.isEmpty() ? SimulationConfig.builder()
                .aircraftCount((int) aircraftRepo.countBySource("SIMULATION"))
                .messageIntervalMs(1000).isRunning(running).build()
                : configs.get(0);
    }

    /**
     * 核心定时任务：每秒更新所有飞机位置并生成【正常】报文
     */
    @Scheduled(fixedRate = 1000)
    public void tick() {
        if (!running) return;

        List<Aircraft> aircraftList = aircraftRepo.findBySource("SIMULATION");
        for (Aircraft ac : aircraftList) {
            // Haversine 位移（每 tick 模拟 30 秒飞行）
            double[] newPos = updatePosition(
                    ac.getLatitude(), ac.getLongitude(),
                    ac.getSpeed(), ac.getHeading(), 30.0
            );
            ac.setLatitude(newPos[0]);
            ac.setLongitude(newPos[1]);
            ac.setAltitude(ac.getAltitude() + (RNG.nextDouble() - 0.5) * 100);
            ac.setAltitude(Math.max(0, Math.min(50000, ac.getAltitude())));
            ac.setHeading((ac.getHeading() + (RNG.nextDouble() - 0.5) * 5 + 360) % 360);
            ac.setLastSeenAt(LocalDateTime.now());
            aircraftRepo.save(ac);

            // 生成一条【正常】ADS-B 报文（source=NORMAL）
            AdsbMessage msg = messageFactory.buildMessage(
                    ac.getIcao(), ac.getAltitude(), ac.getLatitude(), ac.getLongitude(),
                    ac.getSpeed(), ac.getHeading(), "NORMAL"
            );
            messageRepo.save(msg);

            // 纯报文安全检查（检测服务只读报文，不知道来源）
            securityCheckService.check(msg);
        }
    }

    // ====== 工具方法 ======

    private double[] updatePosition(double lat, double lon, double speedKt, double heading, double dtSec) {
        double speedMs = speedKt * 0.514444;
        double dist = speedMs * dtSec;
        double headingRad = Math.toRadians(heading);

        double latRad = Math.toRadians(lat);
        double dLat = (dist * Math.cos(headingRad)) / EARTH_RADIUS;
        double dLon = (dist * Math.sin(headingRad)) / (EARTH_RADIUS * Math.cos(latRad));

        double newLat = lat + Math.toDegrees(dLat);
        double newLon = lon + Math.toDegrees(dLon);

        if (newLat > LAT_MAX || newLat < LAT_MIN) newLat = lat - Math.toDegrees(dLat);
        if (newLon > LON_MAX || newLon < LON_MIN) newLon = lon - Math.toDegrees(dLon);

        return new double[]{newLat, newLon};
    }

    private String randomIcao() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(String.format("%X", RNG.nextInt(16)));
        }
        return sb.toString();
    }

    private String randomCallsign() {
        String[] prefixes = {"CCA", "CES", "CSN", "CSZ", "CHH", "CAL", "KLM", "UAL", "DAL", "BAW"};
        String prefix = prefixes[RNG.nextInt(prefixes.length)];
        int num = 100 + RNG.nextInt(9000);
        return prefix + num;
    }

    private String randomCategory() {
        String[] cats = {"中型 (7000-136000kg)", "大型 (136000-300000kg)", "重型 (>300000kg)"};
        return cats[RNG.nextInt(cats.length)];
    }
}
