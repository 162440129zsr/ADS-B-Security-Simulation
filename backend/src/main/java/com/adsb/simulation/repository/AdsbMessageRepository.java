package com.adsb.simulation.repository;

import com.adsb.simulation.model.AdsbMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AdsbMessageRepository extends JpaRepository<AdsbMessage, Long> {

    List<AdsbMessage> findByIcaoOrderByReceivedAtDesc(String icao);

    List<AdsbMessage> findByMessageType(String messageType);

    long countByIcao(String icao);

    long countBySource(String source);

    List<AdsbMessage> findTop100ByOrderByReceivedAtDesc();
}
