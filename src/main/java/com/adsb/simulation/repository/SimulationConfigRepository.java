package com.adsb.simulation.repository;

import com.adsb.simulation.model.SimulationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SimulationConfigRepository extends JpaRepository<SimulationConfig, Long> {

    /** 获取当前仿真配置（单例模式，始终只取第一条） */
    Optional<SimulationConfig> findTopByOrderByIdAsc();
}
