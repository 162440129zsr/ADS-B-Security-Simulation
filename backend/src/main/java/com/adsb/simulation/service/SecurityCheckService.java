package com.adsb.simulation.service;

import com.adsb.simulation.model.AdsbMessage;
import com.adsb.simulation.model.Aircraft;
import com.adsb.simulation.model.SecurityAlert;
import com.adsb.simulation.repository.AircraftRepository;
import com.adsb.simulation.repository.SecurityAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 安全检测服务 —— 纯报文分析
 *
 * 对每条 ADS-B 报文执行 5 项安全检查，异常写入 security_alert 表。
 *
 * 架构改造要点：
 *  - 检测服务只读 adsb_message 报文流，不知道哪些报文是攻击注入的；
 *  - 检测过程中【不读取】source 字段，只用算法判断；
 *  - source 字段仅在检测完成后用于事后统计检出率/误报率。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityCheckService {

    private final SecurityAlertRepository alertRepo;
    private final AircraftRepository aircraftRepo;

    // 滑动窗口：每架飞机最近 N 个位置点，用于位置跳变中位数检测
    private final Map<String, Deque<double[]>> positionWindows = new ConcurrentHashMap<>();
    private static final int WINDOW_SIZE = 5;

    // 缓存上一条报文（用于高度突变检测）
    private final Map<String, AdsbMessage> lastMessageCache = new ConcurrentHashMap<>();

    // 缓存最近报文 hash，用于重放攻击检测
    private final Map<String, Long> recentMessageCache = new ConcurrentHashMap<>();
    private static final long REPLAY_WINDOW_MS = 60_000;

    // 已触发的告警类型去重（icao:type → true），避免同一异常每秒重复告警
    private final Set<String> activeAlertTypes = ConcurrentHashMap.newKeySet();

    // ===== 检测效果计数器（会话级，随「清空告警」一起重置） =====
    private final AtomicLong injectedChecked = new AtomicLong(); // 已检测的注入报文数
    private final AtomicLong normalChecked = new AtomicLong();   // 已检测的正常报文数
    private final AtomicLong truePositives = new AtomicLong();   // 命中注入报文的告警数
    private final AtomicLong falsePositives = new AtomicLong();  // 命中正常报文的告警数

    private static final double MAX_DISTANCE_KM = 100.0;    // 位置跳变阈值
    private static final double MAX_ALT_CHANGE_FT = 5000.0; // 高度突变阈值
    private static final double MAX_SPEED_KT = 650.0;       // 速度上限

    private static final double EARTH_RADIUS_KM = 6371.0;

    /**
     * 对一条报文执行全部安全检查（纯报文分析，不读取 source 用于判断）
     */
    public void check(AdsbMessage msg) {
        if (msg == null) return;

        String icao = msg.getIcao();
        // 仅用于「检测命中后」将飞机状态标记为异常（可视化），不参与检测判断
        Aircraft aircraft = aircraftRepo.findByIcao(icao).orElse(null);
        boolean detected = false; // 本次检测是否命中异常（与告警去重无关）

        // 1. CRC 校验失败
        if (msg.getCrcValid() != null && !msg.getCrcValid()) {
            detected = true;
            createAlert("CRC校验失败", "红色",
                    "报文 " + msg.getRawHex() + " CRC校验未通过，数据可能已被篡改", msg);
            markAbnormal(aircraft);
        }

        // 2. 速度超限
        if (msg.getSpeed() != null && msg.getSpeed() > MAX_SPEED_KT) {
            detected = true;
            createAlert("速度超限", "橙色",
                    String.format("飞行器 %s 速度 %.0f kt，超过最大限制 %.0f kt", icao, msg.getSpeed(), MAX_SPEED_KT), msg);
            markAbnormal(aircraft);
        }

        // 3. 高度异常（绝对高度超出合理范围）
        if (msg.getAltitude() != null && msg.getAltitude() > 60000) {
            detected = true;
            createAlert("高度异常", "橙色",
                    String.format("飞行器 %s 高度 %.0f ft，超出民航飞行包线", icao, msg.getAltitude()), msg);
            markAbnormal(aircraft);
        }

        // 4. 位置跳变（滑动窗口中位数检测）& 高度突变
        if (msg.getLatitude() != null && msg.getLongitude() != null) {
            // === 位置跳变：滑动窗口中位数检测 ===
            Deque<double[]> window = positionWindows.computeIfAbsent(icao, k -> new ArrayDeque<>());
            window.addLast(new double[]{msg.getLatitude(), msg.getLongitude()});
            if (window.size() > WINDOW_SIZE) window.removeFirst();

            if (window.size() >= 3) {
                double[] lats = window.stream().mapToDouble(p -> p[0]).sorted().toArray();
                double[] lons = window.stream().mapToDouble(p -> p[1]).sorted().toArray();
                double medianLat = lats[lats.length / 2];
                double medianLon = lons[lons.length / 2];

                double dist = haversineDistance(medianLat, medianLon, msg.getLatitude(), msg.getLongitude());
                if (dist > MAX_DISTANCE_KM) {
                    detected = true;
                    createAlert("位置异常跳变", "红色",
                            String.format("飞行器 %s 偏离滑动窗口中位数 %.1f km，可能遭遇GPS欺骗", icao, dist), msg);
                    markAbnormal(aircraft);
                    window.clear(); // 清除异常窗口，重新积累
                }
            }
        }

        // === 高度突变：相邻报文对比 ===
        AdsbMessage last = lastMessageCache.get(icao);
        boolean altitudeAnomaly = false;
        if (last != null && last.getReceivedAt() != null && msg.getReceivedAt() != null) {
            long dtMs = msg.getReceivedAt() - last.getReceivedAt();
            if (dtMs > 0 && dtMs < 30_000) {
                if (msg.getAltitude() != null && last.getAltitude() != null) {
                    double altChange = Math.abs(msg.getAltitude() - last.getAltitude());
                    if (altChange > MAX_ALT_CHANGE_FT) {
                        detected = true;
                        createAlert("高度异常变化", "橙色",
                                String.format("飞行器 %s 在 %.1f 秒内高度变化 %.0f ft", icao, dtMs / 1000.0, altChange), msg);
                        markAbnormal(aircraft);
                        altitudeAnomaly = true;
                    }
                }
            }
        }
        // 高度异常报文不写入缓存，避免下一帧正常报文被误判为“回跳”（消除误报）
        if (!altitudeAnomaly) {
            lastMessageCache.put(icao, msg);
        }

        // 5. 报文重复检测（重放攻击）
        if (msg.getRawHex() != null) {
            String key = icao + ":" + msg.getRawHex();
            Long lastTs = recentMessageCache.get(key);
            long now = System.currentTimeMillis();
            if (lastTs != null && (now - lastTs) < REPLAY_WINDOW_MS) {
                detected = true;
                createAlert("疑似重放攻击", "红色",
                        "飞行器 " + icao + " 短时间内收到完全相同的报文: " + msg.getRawHex(), msg);
                markAbnormal(aircraft);
            }
            recentMessageCache.put(key, now);
        }

        // 定期清理缓存
        if (recentMessageCache.size() > 500) {
            long cleanNow = System.currentTimeMillis();
            recentMessageCache.entrySet().removeIf(e -> cleanNow - e.getValue() > REPLAY_WINDOW_MS);
        }

        // === 事后对照验证（检测完成后读取 source，仅用于统计，不参与检测判断）===
        if ("INJECTED".equals(msg.getSource())) {
            injectedChecked.incrementAndGet();
            if (detected) truePositives.incrementAndGet();
        } else {
            normalChecked.incrementAndGet();
            if (detected) falsePositives.incrementAndGet();
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

    /**
     * 检测效果量化指标（会话级，随清空告警重置）
     *  - 检出率 Recall = 命中注入报文的告警数 / 注入报文总数
     *  - 误报率 FPR    = 命中正常报文的告警数 / 正常报文总数
     *  - 精确率 Precision / F1 分数
     */
    public Map<String, Object> getDetectionMetrics() {
        long injected = injectedChecked.get();
        long normal = normalChecked.get();
        long tp = truePositives.get();
        long fp = falsePositives.get();

        double recall = injected > 0 ? (double) tp / injected : 0;
        double fpr = normal > 0 ? (double) fp / normal : 0;
        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0;
        double f1 = (precision + recall) > 0 ? 2 * precision * recall / (precision + recall) : 0;

        return Map.of(
                "injectedMessages", injected,
                "normalMessages", normal,
                "truePositives", tp,
                "falsePositives", fp,
                "recall", round(recall, 4),
                "fpr", round(fpr, 4),
                "precision", round(precision, 4),
                "f1", round(f1, 4)
        );
    }

    // ====== 内部方法 ======

    /** 清除某飞机的去重缓存（告警被标记已处理时调用） */
    public void clearDedupCache(String icao) {
        activeAlertTypes.removeIf(k -> k.startsWith(icao + ":"));
    }

    /** 删除飞机时清理其所有检测缓存（位置窗口/高度缓存/重放缓存/去重） */
    public void removeAircraft(String icao) {
        positionWindows.remove(icao);
        lastMessageCache.remove(icao);
        recentMessageCache.keySet().removeIf(k -> k.startsWith(icao + ":"));
        activeAlertTypes.removeIf(k -> k.startsWith(icao + ":"));
    }

    /** 清除所有去重缓存并重置检测指标（清空告警时调用） */
    public void clearAllDedupCache() {
        activeAlertTypes.clear();
        injectedChecked.set(0);
        normalChecked.set(0);
        truePositives.set(0);
        falsePositives.set(0);
    }

    private void createAlert(String type, String level, String desc, AdsbMessage msg) {
        String icao = msg.getIcao();
        String dedupKey = icao + ":" + type;
        if (!activeAlertTypes.add(dedupKey)) {
            return; // 该飞机的此类告警已触发且未处理，跳过
        }
        SecurityAlert alert = SecurityAlert.builder()
                .icao(icao)
                .alertType(type)
                .alertLevel(level)
                .description(desc)
                .rawMessage(msg.getRawHex())
                .source(msg.getSource()) // 仅打标签，供事后统计，不参与检测
                .isResolved(false)
                .build();
        alertRepo.save(alert);
        log.warn("[{}] {} — {}", level, type, desc);
    }

    private void markAbnormal(Aircraft ac) {
        if (ac == null) return;
        ac.setStatus("异常");
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

    private double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }
}
