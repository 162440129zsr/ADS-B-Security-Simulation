package com.adsb.simulation.repository;

import com.adsb.simulation.model.SecurityAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SecurityAlertRepository extends JpaRepository<SecurityAlert, Long> {

    List<SecurityAlert> findByIcaoOrderByCreatedAtDesc(String icao);

    List<SecurityAlert> findByAlertLevel(String alertLevel);

    List<SecurityAlert> findByIsResolvedFalseOrderByCreatedAtDesc();

    long countByIsResolvedFalse();

    long countByAlertLevel(String alertLevel);

    long countBySource(String source);
}
