package com.adsb.simulation.repository;

import com.adsb.simulation.model.Aircraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AircraftRepository extends JpaRepository<Aircraft, Long> {

    Optional<Aircraft> findByIcao(String icao);

    List<Aircraft> findByStatus(String status);

    boolean existsByIcao(String icao);
}
