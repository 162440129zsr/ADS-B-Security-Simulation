package com.adsb.simulation.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 仿真控制表
 */
@Entity
@Table(name = "simulation_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 仿真飞机数量 */
    @Builder.Default
    private Integer aircraftCount = 10;

    /** 报文发送间隔（毫秒） */
    @Builder.Default
    private Integer messageIntervalMs = 1000;

    /** 仿真是否运行中 */
    @Builder.Default
    private Boolean isRunning = false;

    /** 攻击模拟类型（NULL/位置欺骗/数据篡改） */
    @Column(length = 30)
    private String attackType;

    private LocalDateTime updatedAt;

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
