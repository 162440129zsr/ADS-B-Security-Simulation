package com.adsb.simulation.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 安全告警表
 */
@Entity
@Table(name = "security_alert", indexes = {
        @Index(name = "idx_alert_icao", columnList = "icao"),
        @Index(name = "idx_alert_created_at", columnList = "createdAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ICAO 24位地址 */
    @Column(length = 6, nullable = false)
    private String icao;

    /** 告警类型 */
    @Column(length = 50)
    private String alertType;

    /** 告警等级：红色 / 橙色 / 黄色 */
    @Column(length = 10)
    @Builder.Default
    private String alertLevel = "黄色";

    /** 告警描述 */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 关联原始报文 */
    @Column(length = 28)
    private String rawMessage;

    /** 是否已处理 */
    @Builder.Default
    private Boolean isResolved = false;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
