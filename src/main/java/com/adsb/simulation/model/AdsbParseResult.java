package com.adsb.simulation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ADS-B 报文解析结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdsbParseResult {

    /** 原始报文 */
    private String rawHex;

    /** DF（下行格式） */
    private Integer df;

    /** ICAO 24位地址 */
    private String icao;

    /** TC（类型码） */
    private Integer tc;

    /** 消息类型描述 */
    private String messageType;

    /** CRC 校验是否通过 */
    private Boolean crcValid;

    /** CRC 余数 */
    private String crcRemainder;

    // ---- 飞机识别 (TC 1-4) ----
    private String callsign;
    private String category;

    // ---- 位置相关 (TC 5-8, 9-18, 20-22) ----
    private Double altitude;
    private String altitudeSource;
    private String oddEvenFrame; // "偶帧" / "奇帧"
    private Integer cprLatRaw;
    private Integer cprLonRaw;
    private Double cprLatComputed;
    private Double cprLonComputed;
    private Double latitude;
    private Double longitude;
    private String positionDecodeMethod;

    // ---- 速度相关 (TC 19) ----
    private Double speed;        // 节
    private Double heading;      // 度
    private Double verticalRate; // ft/min
    private String speedType;    // GS 地速 / AS 空速
    private String verticalDirection; // 上升/下降/平飞

    // ---- 运行状态 (TC 31) ----
    private String adsbVersion;

    // ---- 错误信息 ----
    private String error;
    private String positionError;
    private String velocityError;

    /**
     * 转为有序 Map，方便前端展示
     */
    public Map<String, Object> toOrderedMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("原始报文", rawHex);
        if (error != null) {
            map.put("错误", error);
            return map;
        }
        map.put("DF", df);
        map.put("ICAO", icao);
        map.put("TC", tc);
        map.put("CRC校验", crcValid);
        map.put("CRC余数", crcRemainder);
        map.put("类型", messageType);

        switch (messageType) {
            case "飞机识别":
                map.put("呼号", callsign);
                map.put("飞机类别", category);
                break;
            case "地面位置":
            case "空中位置(气压高度)":
            case "空中位置(GNSS高度)":
                map.put("高度", altitude);
                map.put("高度来源", altitudeSource);
                map.put("奇偶帧", oddEvenFrame);
                map.put("CPR_LAT原始值", cprLatRaw);
                map.put("CPR_LON原始值", cprLonRaw);
                if (cprLatComputed != null) map.put("CPR_LAT计算值", cprLatComputed);
                if (cprLonComputed != null) map.put("CPR_LON计算值", cprLonComputed);
                if (latitude != null) map.put("纬度", latitude);
                if (longitude != null) map.put("经度", longitude);
                if (positionDecodeMethod != null) map.put("位置解码方式", positionDecodeMethod);
                if (positionError != null) map.put("位置解码错误", positionError);
                break;
            case "空中速度":
                map.put("速度", speed + " kt");
                map.put("航向/航迹角", heading + "°");
                map.put("垂直速率", verticalRate + " ft/min");
                map.put("速度类型", speedType);
                map.put("垂直方向", verticalDirection);
                map.put("垂直速率绝对值", Math.abs(verticalRate) + " ft/min");
                if (velocityError != null) map.put("速度解码错误", velocityError);
                break;
            case "飞机运行状态":
                map.put("ADS-B版本", adsbVersion);
                break;
        }
        return map;
    }
}
