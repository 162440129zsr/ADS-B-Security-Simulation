package com.adsb.simulation.service;

import com.adsb.simulation.model.AdsbParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ADS-B 报文解码核心服务
 *
 * 将 Python decode.py 的 pms/pyModeS 逻辑移植为纯 Java 实现。
 * 支持的消息类型：飞机识别、空中位置、地面位置、空中速度、目标状态、运行状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdsbDecoderService {

    private final CrcService crcService;
    private final CprDecoderService cprDecoderService;

    // ==================== 主入口 ====================

    /**
     * 解析单条 ADS-B 报文
     * @param hexMsg 28位十六进制字符串
     * @param timestamp 消息时间戳（用于位置配对）
     */
    public AdsbParseResult parse(String hexMsg, long timestamp) {
        var result = AdsbParseResult.builder()
                .rawHex(hexMsg)
                .build();

        // ---- 1. 基础校验 ----
        if (hexMsg == null || hexMsg.length() != 28) {
            result.setError("报文长度必须为28位十六进制");
            return result;
        }
        if (!hexMsg.matches("[0-9A-Fa-f]{28}")) {
            result.setError("报文包含非法十六进制字符");
            return result;
        }
        String upper = hexMsg.toUpperCase();

        // ---- 2. CRC 校验 ----
        long crcRemainder = crcService.calculate(upper);
        result.setCrcRemainder(String.format("%06X", crcRemainder));
        result.setCrcValid(crcRemainder == 0);

        // ---- 3. 提取基础字段 ----
        int df = (hexCharToInt(upper.charAt(0)) << 1) | (hexCharToInt(upper.charAt(1)) >> 3);
        String icao = upper.substring(2, 8);
        int tc = bitsFromHex(upper, 32, 5); // bits 33-37 (0-indexed: 32-36)

        result.setDf(df);
        result.setIcao(icao);
        result.setTc(tc);

        // ---- 4. 按 Type Code 分发 ----
        if (tc < 1 || tc > 31) {
            result.setMessageType("非ADS-B消息");
            return result;
        }

        // TC 1-4: 飞机识别
        if (tc >= 1 && tc <= 4) {
            decodeIdentification(upper, result);
        }
        // TC 5-8: 地面位置
        else if (tc >= 5 && tc <= 8) {
            decodeSurfacePosition(upper, icao, timestamp, result);
        }
        // TC 9-18: 空中位置（气压高度）
        else if (tc >= 9 && tc <= 18) {
            decodeAirbornePosition(upper, icao, timestamp, "气压高度", result);
        }
        // TC 19: 空中速度
        else if (tc == 19) {
            decodeVelocity(upper, result);
        }
        // TC 20-22: 空中位置（GNSS高度）
        else if (tc >= 20 && tc <= 22) {
            decodeAirbornePosition(upper, icao, timestamp, "GNSS高度", result);
        }
        // TC 28: 扩展 squitter
        else if (tc == 28) {
            result.setMessageType("扩展Squitter");
        }
        // TC 29: 目标状态
        else if (tc == 29) {
            result.setMessageType("目标状态和状态信息");
        }
        // TC 31: 运行状态
        else if (tc == 31) {
            result.setMessageType("飞机运行状态");
            result.setAdsbVersion(decodeVersion(upper));
        } else {
            result.setMessageType("保留/未实现 (TC=" + tc + ")");
        }

        return result;
    }

    // ==================== 飞机识别 (TC 1-4) ====================

    private void decodeIdentification(String hex, AdsbParseResult result) {
        result.setMessageType("飞机识别");

        // 呼号：charset map
        int[] bits = hexToBits128(hex);
        StringBuilder callsign = new StringBuilder();
        for (int i = 40; i <= 87; i += 6) { // 8 个 6-bit 组
            int code = 0;
            for (int j = 0; j < 6; j++) code = (code << 1) | bits[i + j];
            char c = callsignChar(code);
            if (c != '\0') callsign.append(c);
        }
        result.setCallsign(callsign.toString().trim());

        // 飞机类别
        int catCode = bitsFromHex(hex, 5, 3); // bits 6-8
        result.setCategory(aircraftCategory(catCode));
    }

    private char callsignChar(int code) {
        if (code >= 1 && code <= 26) return (char) ('A' + code - 1);
        if (code >= 48 && code <= 57) return (char) ('0' + code - 48);
        if (code == 32) return ' ';
        return '\0';
    }

    private String aircraftCategory(int code) {
        return switch (code) {
            case 0 -> "无类别";
            case 1 -> "轻型 (<7000kg)";
            case 2 -> "中型 (7000-136000kg)";
            case 3 -> "大型 (136000-300000kg)";
            case 4 -> "高涡流大型";
            case 5 -> "重型 (>300000kg)";
            case 6 -> "高性能 (>5g加速)";
            case 7 -> "旋翼机";
            default -> "未定义(" + code + ")";
        };
    }

    // ==================== 位置解码 ====================

    private void decodeAirbornePosition(String hex, String icao, long timestamp,
                                         String altSource, AdsbParseResult result) {
        result.setMessageType("空中位置(" + altSource + ")");
        result.setAltitude(decodeAltitude(hex));
        result.setAltitudeSource(altSource);
        decodePositionCommon(hex, icao, timestamp, false, result);
    }

    private void decodeSurfacePosition(String hex, String icao, long timestamp,
                                        AdsbParseResult result) {
        result.setMessageType("地面位置");
        result.setAltitude(0.0);
        result.setAltitudeSource("地面");
        decodePositionCommon(hex, icao, timestamp, true, result);
    }

    private void decodePositionCommon(String hex, String icao, long timestamp,
                                       boolean isSurface, AdsbParseResult result) {
        // 提取 CPR
        int[] bits = hexToBits128(hex);
        int cprLat = 0, cprLon = 0;
        for (int i = 54; i < 71; i++) cprLat = (cprLat << 1) | bits[i];
        for (int i = 71; i < 88; i++) cprLon = (cprLon << 1) | bits[i];

        result.setCprLatRaw(cprLat);
        result.setCprLonRaw(cprLon);
        result.setCprLatComputed(round(cprLat / 131072.0, 5));
        result.setCprLonComputed(round(cprLon / 131072.0, 5));

        // OE flag
        boolean isOdd = cprDecoderService.extractOddFlag(hex);
        result.setOddEvenFrame(isOdd ? "奇帧" : "偶帧");

        // 缓存 + 尝试配对解码
        cprDecoderService.storeFrame(icao, hex, isOdd, timestamp, cprLat, cprLon);

        double[] pos;
        if (isSurface) {
            pos = cprDecoderService.tryDecodeSurface(icao, 52.3086, 4.7639);
        } else {
            pos = cprDecoderService.tryDecodeAirborne(icao);
        }

        if (pos != null) {
            result.setLatitude(pos[0]);
            result.setLongitude(pos[1]);
            result.setPositionDecodeMethod("奇偶帧配对(全局解码)");
        }
    }

    // ==================== 高度解码 ====================

    private double decodeAltitude(String hex) {
        int[] bits = hexToBits128(hex);
        // bits 41-52 或 48-59
        // 这里取 bits 41-52 (常见位置)
        int altCode = bitsFromHex(hex, 40, 12);
        // 检查 Q bit (bit 48 = index 47 in 0-based from start)
        int qBit = bits[47];
        if (qBit == 1) {
            // Q=1: 25ft 增量
            int n = altCode & 0x1F; // 低5位
            int m = (altCode >>> 5) & 0x7F; // 高7位 (排除 Q bit)
            // 实际: 移除Q bit后重组
            int encoded = ((altCode >>> 1) & 0x7F) << 5 | n;
            return (encoded * 25 - 1000.0);
        } else {
            // Q=0: 100ft 增量 (Gillham编码, 简化处理)
            return (altCode * 100 - 1000.0);
        }
    }

    // ==================== 速度解码 (TC 19) ====================

    private void decodeVelocity(String hex, AdsbParseResult result) {
        result.setMessageType("空中速度");
        try {
            int[] bits = hexToBits128(hex);

            // Subtype (bits 38-40)
            int subType = 0;
            for (int i = 37; i <= 39; i++) subType = (subType << 1) | bits[i];

            if (subType == 1 || subType == 2) {
                // 地速 + 航迹角
                int ewDir = bits[45];  // E/W direction
                int nsDir = bits[56];  // N/S direction

                // E/W 速度 (bits 46-55)
                int ewVel = 0;
                for (int i = 46; i <= 55; i++) ewVel = (ewVel << 1) | bits[i];

                // N/S 速度 (bits 57-66)
                int nsVel = 0;
                for (int i = 57; i <= 66; i++) nsVel = (nsVel << 1) | bits[i];

                double vx = (ewDir == 0 ? 1 : -1) * (ewVel - 1);
                double vy = (nsDir == 0 ? 1 : -1) * (nsVel - 1);
                double spd = Math.sqrt(vx * vx + vy * vy);
                double trk = Math.toDegrees(Math.atan2(vx, vy));
                if (trk < 0) trk += 360;

                result.setSpeed(round(spd, 1));
                result.setHeading(round(trk, 1));
                result.setSpeedType("GS");

                // 垂直速率 (bits 69-77)
                int vrSrc = bits[68];
                int vrVal = 0;
                for (int i = 69; i <= 77; i++) vrVal = (vrVal << 1) | bits[i];
                int vrSign = (vrVal & 0x100) >> 8;
                int vrMag = vrVal & 0xFF;
                double vr = (vrSign == 0 ? 1 : -1) * (vrMag - 1) * 64.0;

                result.setVerticalRate(round(vr, 0));
                result.setVerticalDirection(vr > 0 ? "上升" : vr < 0 ? "下降" : "平飞");
            } else {
                result.setVelocityError("不支持的 Subtype: " + subType);
            }
        } catch (Exception e) {
            result.setVelocityError(e.getMessage());
        }
    }

    // ==================== 版本解码 (TC 31) ====================

    private String decodeVersion(String hex) {
        int version = bitsFromHex(hex, 72, 3);
        return switch (version) {
            case 0 -> "ADS-B v0";
            case 1 -> "ADS-B v1";
            case 2 -> "ADS-B v2";
            default -> "未定义(" + version + ")";
        };
    }

    // ==================== 工具方法 ====================

    private int hexCharToInt(char c) {
        return Character.digit(c, 16);
    }

    /**
     * 从 hex 字符串提取指定范围的 bit 值
     */
    private int bitsFromHex(String hex, int bitStart, int bitCount) {
        int[] bits = hexToBits128(hex);
        int val = 0;
        for (int i = bitStart; i < bitStart + bitCount; i++) {
            val = (val << 1) | bits[i];
        }
        return val;
    }

    private int[] hexToBits128(String hex) {
        int[] bits = new int[128];
        for (int i = 0; i < hex.length(); i++) {
            int nibble = Character.digit(hex.charAt(i), 16);
            for (int j = 0; j < 4; j++) {
                bits[i * 4 + j] = (nibble >> (3 - j)) & 1;
            }
        }
        return bits;
    }

    private double round(double v, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(v * factor) / factor;
    }
}
