package com.adsb.simulation.service;

import com.adsb.simulation.model.AdsbMessage;
import com.adsb.simulation.model.Aircraft;
import com.adsb.simulation.repository.AdsbMessageRepository;
import com.adsb.simulation.repository.AircraftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 攻击注入器 —— 独立于正常仿真的恶意报文通道
 *
 * 核心原则（对应架构改造方案）：
 *  1. 攻击注入器【不】直接修改 Aircraft 对象，而是像真实攻击者一样构造恶意 ADS-B hex 字符串；
 *  2. 恶意报文写入 adsb_message 报文总线，source=INJECTED；
 *  3. 安全检测只读报文流，检测过程中不读取 source 字段。
 *
 * 这样攻击方与检测方不再共享同一 JVM 内存对象，真正形成「信号传输层」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttackInjector {

    private final AircraftRepository aircraftRepo;
    private final AdsbMessageRepository messageRepo;
    private final AdsbMessageFactory messageFactory;
    private final SecurityCheckService securityCheckService;

    private static final Random RNG = new Random();
    private static final String[] ATTACK_TYPES = {"position", "altitude", "speed", "crc", "replay"};

    /**
     * 注入一次攻击。attackType 为空时随机选择。
     * @return 注入结果（success / attackType / victimIcao / victimCallsign）
     */
    public Map<String, Object> inject(String attackType) {
        // 只攻击仿真飞机（有活跃报文流与检测历史）；真实航班是静态快照，无法触发位置/高度/重放检测
        List<Aircraft> all = aircraftRepo.findBySource("SIMULATION");
        if (all.isEmpty()) return Map.of("success", false, "message", "没有仿真飞机可攻击");

        Aircraft victim = all.get(RNG.nextInt(all.size()));
        String type = attackType != null ? attackType : ATTACK_TYPES[RNG.nextInt(ATTACK_TYPES.length)];

        AdsbMessage msg = switch (type) {
            case "position" -> injectPositionSpoof(victim);
            case "altitude" -> injectAltitudeSpoof(victim);
            case "speed"    -> injectSpeedOverrun(victim);
            case "crc"      -> injectCrcCorruption(victim);
            case "replay"   -> injectReplay(victim);
            default         -> null;
        };

        if (msg == null) {
            return Map.of("success", false, "message", "未知攻击类型: " + type);
        }

        // 注入报文总线（source=INJECTED），再交给纯报文检测
        messageRepo.save(msg);
        securityCheckService.check(msg);

        return Map.of(
                "success", true,
                "attackType", type,
                "victimIcao", victim.getIcao(),
                "victimCallsign", victim.getCallsign() != null ? victim.getCallsign() : victim.getIcao()
        );
    }

    // ====== 各攻击类型：只构造恶意 hex，不修改 Aircraft 对象 ======

    /** 位置欺骗：瞬间偏移约 250-500km，CRC 保持有效（真实攻击者可自算 CRC） */
    private AdsbMessage injectPositionSpoof(Aircraft victim) {
        double newLat = victim.getLatitude() + (RNG.nextDouble() - 0.5) * 5;
        double newLon = victim.getLongitude() + (RNG.nextDouble() - 0.5) * 5;
        log.warn("🚨 攻击注入[位置欺骗]: 飞机 {} ({},{}) → ({},{})",
                victim.getIcao(), fmt(victim.getLatitude()), fmt(victim.getLongitude()), fmt(newLat), fmt(newLon));
        return messageFactory.buildMessage(victim.getIcao(), victim.getAltitude(),
                newLat, newLon, victim.getSpeed(), victim.getHeading(), "INJECTED");
    }

    /** 高度篡改：瞬间跳变 ±8000ft */
    private AdsbMessage injectAltitudeSpoof(Aircraft victim) {
        double newAlt = victim.getAltitude() + (RNG.nextBoolean() ? 8000 : -8000);
        newAlt = Math.max(0, Math.min(60000, newAlt));
        log.warn("🚨 攻击注入[高度篡改]: 飞机 {} {} → {} ft",
                victim.getIcao(), fmt(victim.getAltitude()), fmt(newAlt));
        return messageFactory.buildMessage(victim.getIcao(), newAlt,
                victim.getLatitude(), victim.getLongitude(), victim.getSpeed(), victim.getHeading(), "INJECTED");
    }

    /** 速度超限：伪造 700-800kt 超限速度 */
    private AdsbMessage injectSpeedOverrun(Aircraft victim) {
        double newSpeed = 700 + RNG.nextDouble() * 100;
        log.warn("🚨 攻击注入[速度超限]: 飞机 {} 伪造速度 {} kt", victim.getIcao(), fmt(newSpeed));
        return messageFactory.buildMessage(victim.getIcao(), victim.getAltitude(),
                victim.getLatitude(), victim.getLongitude(), newSpeed, victim.getHeading(), "INJECTED");
    }

    /** CRC 篡改：构造合法报文后破坏 PI 字段，使 CRC 校验失败 */
    private AdsbMessage injectCrcCorruption(Aircraft victim) {
        AdsbMessage msg = messageFactory.buildMessage(victim.getIcao(), victim.getAltitude(),
                victim.getLatitude(), victim.getLongitude(), victim.getSpeed(), victim.getHeading(), "INJECTED");
        String broken = messageFactory.corruptPi(msg.getRawHex());
        msg.setRawHex(broken);
        msg.setCrcValid(false);
        log.warn("🚨 攻击注入[CRC篡改]: 飞机 {} 校验码被破坏", victim.getIcao());
        return msg;
    }

    /** 重放攻击：复用该飞机最近一条报文的原始 hex */
    private AdsbMessage injectReplay(Aircraft victim) {
        AdsbMessage msg = messageFactory.buildMessage(victim.getIcao(), victim.getAltitude(),
                victim.getLatitude(), victim.getLongitude(), victim.getSpeed(), victim.getHeading(), "INJECTED");
        List<AdsbMessage> recent = messageRepo.findByIcaoOrderByReceivedAtDesc(victim.getIcao());
        if (!recent.isEmpty()) {
            msg.setRawHex(recent.get(0).getRawHex());
        }
        log.warn("🚨 攻击注入[重放攻击]: 飞机 {} 重放历史报文", victim.getIcao());
        return msg;
    }

    private String fmt(double v) {
        return String.format("%.2f", v);
    }
}
