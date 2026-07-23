package com.adsb.simulation.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * CPR（Compact Position Reporting）解码服务
 *
 * 需要奇帧和偶帧配对才能解码出全局位置。
 * 缓存最近收到的帧，配对成功后计算经纬度。
 */
@Service
public class CprDecoderService {

    // ===== 缓存奇偶帧用于配对 =====
    // icao -> FrameInfo(even)
    private final ConcurrentHashMap<String, CprFrame> evenFrames = new ConcurrentHashMap<>();
    // icao -> FrameInfo(odd)
    private final ConcurrentHashMap<String, CprFrame> oddFrames = new ConcurrentHashMap<>();

    /**
     * 存储一条位置帧
     */
    public void storeFrame(String icao, String hexMsg, boolean isOdd, long timestamp,
                           int cprLat, int cprLon) {
        CprFrame frame = new CprFrame(hexMsg, timestamp, cprLat, cprLon);
        if (isOdd) {
            oddFrames.put(icao, frame);
        } else {
            evenFrames.put(icao, frame);
        }
    }

    /**
     * 尝试用奇偶帧配对解码空中位置
     * @return double[]{lat, lon} 或 null（如果配对不成功）
     */
    public double[] tryDecodeAirborne(String icao) {
        CprFrame even = evenFrames.get(icao);
        CprFrame odd = oddFrames.get(icao);
        if (even == null || odd == null) return null;

        return cprDecode(
                even.cprLat, even.cprLon, even.isOdd(),
                odd.cprLat, odd.cprLon, odd.isOdd(),
                even.timestamp, odd.timestamp,
                false
        );
    }

    /**
     * 尝试用奇偶帧配对解码地面位置
     * @param latRef 参考纬度
     * @param lonRef 参考经度
     */
    public double[] tryDecodeSurface(String icao, double latRef, double lonRef) {
        CprFrame even = evenFrames.get(icao);
        CprFrame odd = oddFrames.get(icao);
        if (even == null || odd == null) return null;

        // 地面位置使用不同的参考点
        return cprDecode(
                even.cprLat, even.cprLon, even.isOdd(),
                odd.cprLat, odd.cprLon, odd.isOdd(),
                even.timestamp, odd.timestamp,
                false
        );
    }

    /**
     * 用参考位置解码单条位置报文（无需奇偶配对）
     */
    public double[] decodeWithReference(String hexMsg, double latRef, double lonRef) {
        int[] cpr = extractCpr(hexMsg);
        boolean isOdd = extractOddFlag(hexMsg);

        // NZ 常量
        double nz = 15.0; // 空中位置

        double dLat;
        if (isOdd) {
            dLat = 360.0 / 59.0;
        } else {
            dLat = 360.0 / 60.0;
        }

        // 纬度
        int j = (int) Math.floor(latRef / dLat)
                + (int) Math.floor(0.5 + ((latRef % dLat) / dLat) - (double) cpr[0] / 131072.0);
        double lat = dLat * (j + (double) cpr[0] / 131072.0);
        lat = normalizeLat(lat);

        // 经度
        double dLon;
        if (isOdd) {
            dLon = 360.0 / (nz > 0 ? nz : 1);
        } else {
            dLon = 360.0 / (nz - 1 > 0 ? nz - 1 : 1);
        }

        // 根据纬度重新计算 NL
        int nl = computeNL(lat);
        if (nl > 0) {
            dLon = isOdd ? 360.0 / nl : 360.0 / Math.max(nl - 1, 1);
        }

        int m = (int) Math.floor(lonRef / dLon)
                + (int) Math.floor(0.5 + ((lonRef % dLon) / dLon) - (double) cpr[1] / 131072.0);
        double lon = dLon * (m + (double) cpr[1] / 131072.0);
        lon = normalizeLon(lon);

        return new double[]{round(lat, 5), round(lon, 5)};
    }

    /**
     * CPR 全局解码（奇偶帧配对）
     */
    private double[] cprDecode(int cprLatEven, int cprLonEven, boolean evenIsOdd,
                                int cprLatOdd, int cprLonOdd, boolean oddIsOdd,
                                long tEven, long tOdd, boolean isSurface) {
        // ===== 纬度解码 =====
        double dLatEven = 360.0 / (isSurface ? 90.0 : 60.0);
        double dLatOdd  = 360.0 / (isSurface ? 90.0 : 59.0);

        // 纬度索引
        double j = Math.floor(59.0 * cprLatEven / 131072.0 - 60.0 * cprLatOdd / 131072.0 + 0.5);

        // 偶帧纬度
        double latEven = dLatEven * (mod(j, 60) + cprLatEven / 131072.0);
        latEven = normalizeLat(latEven);

        // 奇帧纬度
        double latOdd  = dLatOdd  * (mod(j, 59) + cprLatOdd  / 131072.0);
        latOdd  = normalizeLat(latOdd);

        // 选择把 t 较小的帧设为偶数帧
        double lat;
        if (tEven < tOdd) {
            lat = latEven;
        } else {
            lat = latOdd;
        }

        // ===== 经度解码 =====
        int nl = computeNL(lat);
        if (nl == 0) return null;

        double dLonEven = 360.0 / (nl > 1 ? nl - 1 : 1); // 确保不除零
        if (nl == 1) dLonEven = 360.0;
        double dLonOdd  = 360.0 / nl;

        double m = Math.floor(
                cprLonEven / 131072.0 * (nl - 1) - cprLonOdd / 131072.0 * nl + 0.5
        );

        double lon;
        if (tEven < tOdd) {
            lon = dLonEven * (mod(m, Math.max(nl - 1, 1)) + cprLonEven / 131072.0);
        } else {
            lon = dLonOdd  * (mod(m, nl) + cprLonOdd  / 131072.0);
        }
        lon = normalizeLon(lon);

        return new double[]{round(lat, 5), round(lon, 5)};
    }

    /**
     * 计算 NL（纬度带的数量）
     */
    private int computeNL(double lat) {
        if (lat == 0) return 59;
        if (Math.abs(lat) >= 87.0) return 1;
        double nz = 15.0;
        return (int) Math.floor(
                2.0 * Math.PI / Math.acos(1.0 - (1.0 - Math.cos(Math.PI / (2.0 * nz)))
                        / (Math.cos(Math.PI / 180.0 * Math.abs(lat)) * Math.cos(Math.PI / 180.0 * Math.abs(lat))))
        );
    }

    /**
     * 从报文中提取 CPR 值
     */
    private int[] extractCpr(String hexMsg) {
        int[] bits = hexToBits128(hexMsg);
        // bit 54: F flag, bits 55-71: CPR-LAT (17 bits), bits 72-88: CPR-LON (17 bits)
        int cprLat = 0, cprLon = 0;
        for (int i = 54; i < 71; i++) cprLat = (cprLat << 1) | bits[i];
        for (int i = 71; i < 88; i++) cprLon = (cprLon << 1) | bits[i];
        return new int[]{cprLat, cprLon};
    }

    /**
     * 从报文中提取奇偶标志
     */
    public boolean extractOddFlag(String hexMsg) {
        int[] bits = hexToBits128(hexMsg);
        return bits[53] == 1; // bit 54 (0-indexed: 53)
    }

    /**
     * 28 位 hex → 128 bits 数组
     */
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

    private double normalizeLat(double lat) {
        if (lat >= 270) return lat - 360;
        if (lat <= -90) return lat + 360;
        if (lat > 90)  return lat - 180;
        return lat;
    }

    private double normalizeLon(double lon) {
        if (lon >= 180) return lon - 360;
        if (lon <= -180) return lon + 360;
        return lon;
    }

    private double mod(double a, double b) {
        double r = a % b;
        return r < 0 ? r + b : r;
    }

    private double round(double v, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(v * factor) / factor;
    }

    // ===== 内部类 =====
    static class CprFrame {
        String hexMsg;
        long timestamp;
        int cprLat;
        int cprLon;

        CprFrame(String hexMsg, long timestamp, int cprLat, int cprLon) {
            this.hexMsg = hexMsg;
            this.timestamp = timestamp;
            this.cprLat = cprLat;
            this.cprLon = cprLon;
        }

        boolean isOdd() {
            return false; // 由外在 OE flag 决定，这里只是容器
        }
    }
}
