package com.adsb.simulation.service;

import com.adsb.simulation.model.Aircraft;
import com.adsb.simulation.repository.AircraftRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 真实 ADS-B 数据源接入服务（改进建议 #8）
 *
 * 从 OpenSky Network 公开 API 拉取真实航班状态（已解码的 ICAO/呼号/经纬度/高度/速度/航向），
 * 转换为 Aircraft 实体（source=REAL），接入本系统地图与检测链路。
 *
 * OpenSky states 数组字段：
 *   [0]=icao24 [1]=callsign [5]=longitude [6]=latitude
 *   [7]=baro_altitude(m) [9]=velocity(m/s) [10]=true_track(deg)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealDataService {

    private final AircraftRepository aircraftRepo;
    private final ObjectMapper objectMapper;

    // 中国空域（含周边）范围
    private static final String OPENSKY_URL =
            "https://opensky-network.org/api/states/all?lamin=18&lomin=95&lamax=45&lomax=130";

    private static final double M_TO_FT = 3.28084;
    private static final double MS_TO_KT = 1.94384;

    /**
     * 从 OpenSky 拉取真实航班并导入（upsert）
     */
    public Map<String, Object> importRealAircraft() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(OPENSKY_URL))
                    .timeout(Duration.ofSeconds(25))
                    .header("User-Agent", "adsb-simulation/0.1")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode states = root.get("states");
            if (states == null || !states.isArray() || states.size() == 0) {
                return Map.of("success", false, "message", "OpenSky 当前无覆盖该空域的航班数据");
            }

            int imported = 0;
            for (JsonNode s : states) {
                if (!s.isArray() || s.size() < 11) continue;
                JsonNode lonNode = s.get(5), latNode = s.get(6);
                if (lonNode == null || lonNode.isNull() || latNode == null || latNode.isNull()) continue;

                String icao = s.get(0).asText();
                String callsign = s.get(1) != null && !s.get(1).isNull() ? s.get(1).asText().trim() : "";
                double lat = latNode.asDouble();
                double lon = lonNode.asDouble();
                double altFt = s.get(7) != null && !s.get(7).isNull() ? s.get(7).asDouble() * M_TO_FT : 0;
                double speedKt = s.get(9) != null && !s.get(9).isNull() ? s.get(9).asDouble() * MS_TO_KT : 0;
                double heading = s.get(10) != null && !s.get(10).isNull() ? s.get(10).asDouble() : 0;

                Aircraft ac = aircraftRepo.findByIcao(icao).orElse(null);
                if (ac == null) {
                    ac = Aircraft.builder()
                            .icao(icao)
                            .callsign(callsign.isEmpty() ? null : callsign)
                            .category("真实航班")
                            .latitude(lat).longitude(lon).altitude(altFt)
                            .speed(speedKt).heading(heading)
                            .status("正常").source("REAL")
                            .build();
                } else {
                    if (!callsign.isEmpty()) ac.setCallsign(callsign);
                    ac.setLatitude(lat); ac.setLongitude(lon);
                    ac.setAltitude(altFt); ac.setSpeed(speedKt); ac.setHeading(heading);
                    ac.setLastSeenAt(LocalDateTime.now());
                }
                aircraftRepo.save(ac);
                imported++;
            }
            log.info("已从 OpenSky 导入 {} 架真实航班", imported);
            return Map.of("success", true, "imported", imported);
        } catch (Exception e) {
            log.error("导入真实航班失败", e);
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    /**
     * 清除所有真实航班数据
     */
    @Transactional
    public Map<String, Object> clearRealAircraft() {
        long count = aircraftRepo.countBySource("REAL");
        aircraftRepo.deleteBySource("REAL");
        log.info("已清除 {} 架真实航班", count);
        return Map.of("success", true, "deleted", count);
    }
}
