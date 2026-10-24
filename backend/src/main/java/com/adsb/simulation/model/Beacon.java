package com.adsb.simulation.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 信标机 —— 周期性自主发射测试信号的固定目标
 *
 * 对应真实 ADS-B 地面站系统的信标机（技术手册 §6.7）：
 * 为 1090ES 地面站提供稳定信号源，用于检测接收性能。
 * 当天馈系统性能下降时，信标信号强度减弱，系统产生告警。
 */
@Entity
@Table(name = "beacon")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Beacon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 信标机标识（固定 S 模式地址风格） */
    @Column(length = 6, nullable = false, unique = true)
    private String icao;

    /** 呼号 */
    @Column(length = 16)
    private String callsign;

    /** 信号强度（0~100%，模拟射频信号幅度） */
    private Double signalStrength;

    /** 告警门限（低于此值判定为信号弱） */
    private Double threshold;

    /** 状态：正常 / 信号弱 */
    @Column(length = 10)
    @Builder.Default
    private String status = "正常";

    /** 发射间隔（秒） */
    @Builder.Default
    private Integer txIntervalSec = 5;

    /** 最近一次发射时间 */
    private LocalDateTime lastTxAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        lastTxAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
