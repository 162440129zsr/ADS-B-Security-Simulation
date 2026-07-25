package com.adsb.simulation.service;

import com.adsb.simulation.model.AdsbMessage;
import com.adsb.simulation.model.Aircraft;
import com.adsb.simulation.model.SecurityAlert;
import com.adsb.simulation.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 安全检测服务 —— 参考 RID 项目 collision_detection.py 的分级告警模式
 * 对每条 ADS-B 报文执行 5 项安全检查，异常写入 security_alert 表
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityCheckService {

    private final SecurityAlertRepository alertRepo;
    private final AircraftRepository aircraftRepo;
    private final AdsbMessageRepository messageRepo;

    // 缓存每架飞机的上一条报文，用于位置跳变/高度突变检测
    private final Map<String, AdsbMessage> lastMessageCache = new ConcurrentHashMap<>();

    // 缓存最近报文 hash，用于重放攻击检测
    private final Map<String, Long> recentMessageCache = new ConcurrentHashMap<>();
    private static final long REPLAY_WINDOW_MS = 60_000;

    // 已触发的告警类型去重（icao:type → true），避免同一异常每秒重复告警
    private final Set<String> activeAlertTypes = ConcurrentHashMap.newKeySet();

    private static final double MAX_DISTANCE_KM = 100.0;   // 30秒内位置跳变阈值
    private static final double MAX_ALT_CHANGE_FT = 5000.0; // 30秒内高度突变阈值
    private static final double MAX_SPEED_KT = 650.0;       // 速度上限

    private static final double EARTH_RADIUS_KM = 6371.0;

    /**
     * 对一条报文执行全部安全检查，在 SimulationService.tick() 中调用
     */
    public void check(AdsbMessage msg, Aircraft aircraft) {
        if (msg == null || aircraft == null) return;

        String icao = msg.getIcao();

        // 1. CRC 校验失败
        if (msg.getCrcValid() != null && !msg.getCrcValid()) {
            createAlert(icao, "CRC校验失败", "红色",
                    "报文 " + msg.getRawHex() + " CRC校验未通过，数据可能已被篡改", msg.getRawHex());
        }

        // 2. 速度超限
        if (msg.getSpeed() != null && msg.getSpeed() > MAX_SPEED_KT) {
            createAlert(icao, "速度超限", "橙色",
                    String.format("飞行器 %s 速度 %.0f kt，超过最大限制 %.0f kt", icao, msg.getSpeed(), MAX_SPEED_KT),
                    msg.getRawHex());
        }

        // 3. 高度异常（绝对高度超出合理范围）
        if (msg.getAltitude() != null && msg.getAltitude() > 60000) {
            createAlert(icao, "高度异常", "橙色",
                    String.format("飞行器 %s 高度 %.0f ft，超出民航飞行包线", icao, msg.getAltitude()),
                    msg.getRawHex());
        }

        // 4. 位置跳变 & 高度突变（需要上一条报文对比）
        AdsbMessage last = lastMessageCache.get(icao);
        if (last != null && last.getReceivedAt() != null && msg.getReceivedAt() != null) {
            long dtMs = msg.getReceivedAt() - last.getReceivedAt();
            if (dtMs > 0 && dtMs < 30_000) { // 30秒内的两条报文
                // 位置跳变
                if (msg.getLatitude() != null && msg.getLongitude() != null
                        && last.getLatitude() != null && last.getLongitude() != null) {
                    double dist = haversineDistance(
                            last.getLatitude(), last.getLongitude(),
                            msg.getLatitude(), msg.getLongitude());
                    if (dist > MAX_DISTANCE_KM) {
                        createAlert(icao, "位置异常跳变", "红色",
                                String.format("飞行器 %s 在 %.1f 秒内位移 %.1f km，可能遭遇GPS欺骗",
                                        icao, dtMs / 1000.0, dist),
                                msg.getRawHex());
                        // 更新飞机状态为异常
                        updateAircraftStatus(aircraft, "异常");
                    }
                }
                // 高度突变
                if (msg.getAltitude() != null && last.getAltitude() != null) {
                    double altChange = Math.abs(msg.getAltitude() - last.getAltitude());
                    if (altChange > MAX_ALT_CHANGE_FT) {
                        createAlert(icao, "高度异常变化", "橙色",
                                String.format("飞行器 %s 在 %.1f 秒内高度变化 %.0f ft", icao, dtMs / 1000.0, altChange),
                                msg.getRawHex());
                        updateAircraftStatus(aircraft, "异常");
                    }
                }
            }
        }
        lastMessageCache.put(icao, msg);

        // 5. 报文重复检测（重放攻击）
        if (msg.getRawHex() != null) {
            String key = icao + ":" + msg.getRawHex();
            Long lastTs = recentMessageCache.get(key);
            long now = System.currentTimeMillis();
            if (lastTs != null && (now - lastTs) < REPLAY_WINDOW_MS) {
                createAlert(icao, "疑似重放攻击", "红色",
                        "飞行器 " + icao + " 短时间内收到完全相同的报文: " + msg.getRawHex(),
                        msg.getRawHex());
            }
            recentMessageCache.put(key, now);
        }

        // 定期清理缓存
        if (recentMessageCache.size() > 500) {
            long cleanNow = System.currentTimeMillis();
            recentMessageCache.entrySet().removeIf(e -> cleanNow - e.getValue() > REPLAY_WINDOW_MS);
        }
    }

    // ====== 告警统计 ======

    public Map<String, Object> getAlertStats() {
        return Map.of(
                "total", alertRepo.count(),
                "unresolved", alertRepo.countByIsResolvedFalse(),
                "red", alertRepo.countByAlertLevel("红色"),
                "orange", alertRepo.countByAlertLevel("橙色"),
                "yellow", alertRepo.countByAlertLevel("黄色")
        );
    }

    // ====== 内部方法 ======

    /**
     * 清除某飞机的去重缓存（告警被标记已处理时调用）
     */
    public void clearDedupCache(String icao) {
        activeAlertTypes.removeIf(k -> k.startsWith(icao + ":"));
    }

    private void createAlert(String icao, String type, String level, String desc, String rawMsg) {
        String dedupKey = icao + ":" + type;
        if (!activeAlertTypes.add(dedupKey)) {
            return; // 该飞机的此类告警已触发且未处理，跳过
        }
        SecurityAlert alert = SecurityAlert.builder()
                .icao(icao)
                .alertType(type)
                .alertLevel(level)
                .description(desc)
                .rawMessage(rawMsg)
                .isResolved(false)
                .build();
        alertRepo.save(alert);
        log.warn("[{}] {} — {}", level, type, desc);
    }

    private void updateAircraftStatus(Aircraft ac, String status) {
        ac.setStatus(status);
        aircraftRepo.save(ac);
    }

    private double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}
