package com.adsb.simulation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling  // 后续用于定时产生模拟报文
public class AdsbSimulationApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdsbSimulationApplication.class, args);
    }
}
