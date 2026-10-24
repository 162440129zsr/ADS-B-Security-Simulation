package com.adsb.simulation.service;

import com.adsb.simulation.model.AdsbMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADS-B 报文工厂
 *
 * 统一负责「字段 → 28位 hex 报文」的构造与 CRC 计算。
 * 正常仿真（SimulationService）与攻击注入（AttackInjector）共用同一套编码逻辑，
 * 保证攻击注入的报文与真实广播在格式上完全一致。
 */
@Service
@RequiredArgsConstructor
public class AdsbMessageFactory {

    private final CrcService crcService;
    private static final Random RNG = new Random();

    // 每架飞机的 CPR 奇偶帧计数器：真实 ADS-B 交替发送奇帧/偶帧。
    // 保证相邻报文 hex 唯一，避免「同位置+同高度」的两帧被误判为重放攻击。
    private final Map<String, Integer> frameCounters = new ConcurrentHashMap<>();

    /**
     * 按 ADS-B 格式生成空中位置报文 hex（28 位）
     * 格式: DF(1) + CA(1) + ICAO(6) + DATA(14) + PI(6)
     * DF=17 (10001), CA=0 (000), 首字节 0x8D
     */
    public String buildPositionHex(String icao, double altitude, double latitude, double longitude) {
        StringBuilder sb = new StringBuilder();
        sb.append("8D");                    // DF=17, CA=0
        sb.append(icao);                    // 6 hex, ICAO 地址

        int tc = 11;                        // Type Code: 空中位置(气压高度)
        int ss = 0;                         // Surveillance Status
        int saf = 0;                        // Single Antenna Flag
        // ALT: 气压高度编码 (Q=1, 25ft 增量)
        int altRaw = (int) ((altitude + 1000) / 25);
        altRaw = Math.max(0, Math.min(0xFFF, altRaw));
        int t = 1;                          // Time flag
        int f = frameCounters.merge(icao, 1, Integer::sum) & 1;  // CPR odd/even 交替

        // CPR 编码（简化）
        int cprLat = (int) ((latitude - Math.floor(latitude)) * 131072);
        int cprLon = (int) ((longitude - Math.floor(longitude)) * 131072);

        // 组装 56 bits ME 字段
        long me = 0;
        me = (me << 5)  | (tc & 0x1F);
        me = (me << 2)  | (ss & 0x03);
        me = (me << 1)  | (saf & 0x01);
        me = (me << 12) | (altRaw & 0xFFF);
        me = (me << 1)  | (t & 0x01);
        me = (me << 1)  | (f & 0x01);
        me = (me << 17) | (cprLat & 0x1FFFF);
        me = (me << 17) | (cprLon & 0x1FFFF);

        sb.append(String.format("%014X", me)); // 14 hex chars

        // PI 字段：真实 CRC-24 编码
        String body = sb.toString(); // 22 hex chars
        sb.append(crcService.generate(body));

        return sb.toString().toUpperCase();
    }

    /**
     * 构造一条完整的 ADS-B 报文记录（含 CRC 校验结果）
     */
    public AdsbMessage buildMessage(String icao, double altitude, double latitude, double longitude,
                                    double speed, double heading, String source) {
        String rawHex = buildPositionHex(icao, altitude, latitude, longitude);
        return AdsbMessage.builder()
                .icao(icao)
                .rawHex(rawHex)
                .df(17).tc(11)
                .messageType("空中位置(气压高度)")
                .altitude(altitude)
                .latitude(latitude)
                .longitude(longitude)
                .speed(speed)
                .heading(heading)
                .crcValid(crcService.isValid(rawHex))
                .isEncrypted(false)
                .source(source)
                .receivedAt(System.currentTimeMillis())
                .build();
    }

    /**
     * 破坏报文的 PI 字段（最后 6 位），使 CRC 校验失败。
     * 模拟攻击者篡改报文字段但保留错误校验码。
     */
    public String corruptPi(String hex) {
        if (hex == null || hex.length() != 28) return hex;
        StringBuilder broken = new StringBuilder(hex);
        for (int i = 22; i < 28; i++) {
            broken.setCharAt(i, "0123456789ABCDEF".charAt(RNG.nextInt(16)));
        }
        return broken.toString();
    }
}
