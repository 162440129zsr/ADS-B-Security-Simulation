package com.adsb.simulation.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 飞行器信息表
 */
@Entity
@Table(name = "aircraft")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Aircraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ICAO 24位地址（唯一标识） */
    @Column(length = 6, nullable = false, unique = true)
    private String icao;

    /** 航班呼号 */
    @Column(length = 8)
    private String callsign;

    /** 飞机类别 */
    @Column(length = 50)
    private String category;

    /** 当前纬度 */
    private Double latitude;

    /** 当前经度 */
    private Double longitude;

    /** 当前高度（英尺） */
    private Double altitude;

    /** 当前速度（节） */
    private Double speed;

    /** 航向角 */
    private Double heading;

    /** 状态：正常 / 异常 */
    @Column(length = 10)
    @Builder.Default
    private String status = "正常";

    /** 数据来源：SIMULATION（仿真） / REAL（真实 ADS-B 数据源） */
    @Column(length = 12)
    @Builder.Default
    private String source = "SIMULATION";

    /** 最后更新时间 */
    private LocalDateTime lastSeenAt;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        lastSeenAt = LocalDateTime.now();
    }
}
