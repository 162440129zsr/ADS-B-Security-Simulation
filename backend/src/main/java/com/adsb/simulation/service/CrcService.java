package com.adsb.simulation.service;

import org.springframework.stereotype.Service;

import java.math.BigInteger;

/**
 * ADS-B CRC-24 校验服务
 *
 * 生成多项式: 0xFFFA0480
 * G(x) = x^24 + x^23 + x^22 + x^21 + x^20 + x^19 + x^18 + x^17 + x^16
 *      + x^15 + x^14 + x^13 + x^12 + x^10 + x^3 + 1
 *
 * 算法流程：
 *   发送端：前 88 bit 报文体 + 24 bit 零 → 模二除法 → 余数填入 PI 字段
 *   接收端：完整 112 bit → 模二除法 → 余数为 0 则通过
 *
 * 使用 BigInteger 处理 112-bit 长度，避免 long 溢出。
 */
@Service
public class CrcService {

    // 生成多项式（25 位：24 位系数 + 隐含最高位）
    private static final BigInteger GENERATOR = new BigInteger("1FFF409", 16);

    /**
     * 为前 22 个 hex 字符（88 bit 报文体）生成 6 位 CRC hex
     */
    public String generate(String body) {
        if (body == null || body.length() != 22) {
            throw new IllegalArgumentException("报文 body 必须为 22 位 hex");
        }
        String hexWithZeroParity = body + "000000";
        long remainder = crcRemainder(hexWithZeroParity);
        return String.format("%06X", remainder);
    }

    /**
     * 计算完整 28 位 hex 报文的 CRC 余数。余数 == 0 表示校验通过。
     */
    public long calculate(String hexMsg) {
        if (hexMsg == null || hexMsg.length() != 28) return -1;
        return crcRemainder(hexMsg);
    }

    public boolean isValid(String hexMsg) {
        return calculate(hexMsg) == 0;
    }

    // ====== 内部 ======

    private long crcRemainder(String hex28) {
        BigInteger dividend = new BigInteger(hex28, 16); // 112 bits
        for (int i = 111; i >= 24; i--) {
            if (dividend.testBit(i)) {
                dividend = dividend.xor(GENERATOR.shiftLeft(i - 24));
            }
        }
        return dividend.longValue() & 0xFFFFFFL;
    }
}
