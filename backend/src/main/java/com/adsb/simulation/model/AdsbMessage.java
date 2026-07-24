package com.adsb.simulation.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ADS-B 报文记录表
 */
@Entity
@Table(name = "adsb_message", indexes = {
        @Index(name = "idx_icao", columnList = "icao"),
        @Index(name = "idx_received_at", columnList = "receivedAt"),
        @Index(name = "idx_msg_type", columnList = "messageType")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdsbMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ICAO 24位地址 */
    @Column(length = 6, nullable = false)
    private String icao;

    /** 原始28位十六进制报文 */
    @Column(length = 28, nullable = false)
    private String rawHex;

    /** 下行格式 DF */
    private Integer df;

    /** 类型码 TC */
    private Integer tc;

    /** 消息类型描述 */
    @Column(length = 30)
    private String messageType;

    /** 高度（英尺） */
    private Double altitude;

    /** 纬度 */
    private Double latitude;

    /** 经度 */
    private Double longitude;

    /** 速度（节） */
    private Double speed;

    /** 航向角 */
    private Double heading;

    /** CRC 校验是否通过 */
    private Boolean crcValid;

    /** 是否加密报文 */
    @Builder.Default
    private Boolean isEncrypted = false;

    /** 接收时间戳（毫秒） */
    private Long receivedAt;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
