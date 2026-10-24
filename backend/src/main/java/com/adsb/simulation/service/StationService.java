package com.adsb.simulation.service;

import com.adsb.simulation.model.Beacon;
import com.adsb.simulation.model.SecurityAlert;
import com.adsb.simulation.repository.AircraftRepository;
import com.adsb.simulation.repository.AdsbMessageRepository;
import com.adsb.simulation.repository.BeaconRepository;
import com.adsb.simulation.repository.SecurityAlertRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 地面站服务 —— 信标机自检 + 站点状态报告
 *
 * 对应真实 ADS-B 地面站系统：
 *  - 信标机周期发射测试信号，信号强度监视，性能下降预警告警（技术手册 §6.7）
 *  - CAT023 站点状态报告，工作状态分级：良好 / 降级 / 过载 / 故障（操作手册 §4.6.4）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StationService {

    private final BeaconRepository beaconRepo;
    private final SecurityAlertRepository alertRepo;
    private final AircraftRepository aircraftRepo;
    private final AdsbMessageRepository messageRepo;
    private final SimulationService simulationService;

    private static final String BEACON_ICAO = "BEA001";
    private static final double DEFAULT_STRENGTH = 85.0;
    private static final double DEFAULT_THRESHOLD = 30.0;
    private static final int OVERLOAD_TARGET_COUNT = 500;

    /** 初始化信标机（仅在无数据时创建） */
    @PostConstruct
    public void initBeacon() {
        if (beaconRepo.count() == 0) {
            Beacon b = Beacon.builder()
                    .icao(BEACON_ICAO)
                    .callsign("信标机")
                    .signalStrength(DEFAULT_STRENGTH)
                    .threshold(DEFAULT_THRESHOLD)
                    .status("正常")
                    .txIntervalSec(5)
                    .build();
            beaconRepo.save(b);
            log.info("信标机已初始化：{} 信号强度 {}%，门限 {}%", BEACON_ICAO, DEFAULT_STRENGTH, DEFAULT_THRESHOLD);
        }
    }

    /**
     * 信标机周期发射（更新最近发射时间），并监视信号强度
     */
    @Scheduled(fixedRate = 2000)
    public void beaconTick() {
        beaconRepo.findAll().forEach(b -> {
            b.setLastTxAt(LocalDateTime.now());
            beaconRepo.save(b);

            // 信号强度监视：低于门限 → 告警（仅状态翻转时告警一次）
            double strength = b.getSignalStrength() != null ? b.getSignalStrength() : 0;
            double threshold = b.getThreshold() != null ? b.getThreshold() : DEFAULT_THRESHOLD;
            if (strength < threshold && !"信号弱".equals(b.getStatus())) {
                b.setStatus("信号弱");
                beaconRepo.save(b);
                createBeaconAlert(b, strength, threshold);
            } else if (strength >= threshold && !"正常".equals(b.getStatus())) {
                b.setStatus("正常");
                beaconRepo.save(b);
                log.info("信标机信号恢复：{}%", strength);
            }
        });
    }

    /** 获取信标机状态 */
    public Beacon getBeacon() {
        return beaconRepo.findAll().stream().findFirst().orElse(null);
    }

    /** 设置信标信号强度（用于模拟天馈系统性能下降 / 恢复） */
    public Map<String, Object> setBeaconStrength(double strength) {
        Beacon b = getBeacon();
        if (b == null) {
            initBeacon();
            b = getBeacon();
        }
        strength = Math.max(0, Math.min(100, strength));
        b.setSignalStrength(strength);
        beaconRepo.save(b);
        // 立即执行一次监视判定，保证响应及时
        beaconTick();
        return Map.of(
                "success", true,
                "icao", b.getIcao(),
                "signalStrength", b.getSignalStrength(),
                "threshold", b.getThreshold(),
                "status", b.getStatus()
        );
    }

    /**
     * 站点状态报告（CAT023 风格）
     * 工作状态分级：良好 / 降级 / 过载 / 故障
     */
    public Map<String, Object> getStationStatus() {
        Beacon beacon = getBeacon();
        long targetCount = aircraftRepo.count();
        long messageCount = messageRepo.count();
        boolean running = simulationService.isRunning();

        String workingStatus;
        if (!running) {
            workingStatus = "故障";
        } else if (targetCount > OVERLOAD_TARGET_COUNT) {
            workingStatus = "过载";
        } else if (beacon != null && "信号弱".equals(beacon.getStatus())) {
            workingStatus = "降级";
        } else {
            workingStatus = "良好";
        }

        return Map.of(
                "stationName", "ADS-B 仿真地面站",
                "workingStatus", workingStatus,
                "targetCount", targetCount,
                "capacity", OVERLOAD_TARGET_COUNT,
                "messageCount", messageCount,
                "gnssStatus", "正常",
                "simulationRunning", running,
                "beaconStatus", beacon != null ? beacon.getStatus() : "未安装",
                "beaconSignalStrength", beacon != null ? beacon.getSignalStrength() : null,
                "updatedAt", LocalDateTime.now().toString()
        );
    }

    private void createBeaconAlert(Beacon b, double strength, double threshold) {
        SecurityAlert alert = SecurityAlert.builder()
                .icao(b.getIcao())
                .alertType("信标信号异常")
                .alertLevel("橙色")
                .description(String.format("信标机信号强度 %.0f%%，低于门限 %.0f%%，疑似天馈系统性能下降", strength, threshold))
                .rawMessage(null)
                .source("STATION") // 站点级告警，不参与 ADS-B 报文检测指标统计
                .isResolved(false)
                .build();
        alertRepo.save(alert);
        log.warn("[橙色] 信标信号异常 — {} 强度 {}% 低于门限 {}%", b.getIcao(), strength, threshold);
    }
}
