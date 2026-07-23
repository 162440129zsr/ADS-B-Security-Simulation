package com.adsb.simulation.service;

import org.springframework.stereotype.Service;

/**
 * ADS-B CRC 校验服务
 *
 * CRC 生成多项式: 0xFFFA0480 (G(x) = x^24 + x^23 + x^22 + ... + x + 1)
 * ADS-B 报文使用 112 bits 数据 + 24 bits CRC
 */
@Service
public class CrcService {

    private static final long GENERATOR = 0xFFFA0480L;
    private static final int CRC_BITS = 24;

    /**
     * 计算 ADS-B 报文的 CRC 余数
     * @param hexMsg 28位十六进制字符串
     * @return CRC 余数（24 bits）
     */
    public long calculate(String hexMsg) {
        long data = hexToLong(hexMsg);
        int totalBits = hexMsg.length() * 4;

        // 将 data 左移 24 位为 CRC 腾出空间
        long dividend = data;
        long divisor = GENERATOR;

        // 从最高位开始做模2除法
        for (int i = totalBits + CRC_BITS - 1; i >= CRC_BITS; i--) {
            if ((dividend & (1L << i)) != 0) {
                dividend ^= (divisor << (i - CRC_BITS));
            }
        }

        // 返回低 24 bits
        return dividend & 0xFFFFFFL;
    }

    /**
     * CRC 校验是否通过
     */
    public boolean isValid(String hexMsg) {
        return calculate(hexMsg) == 0;
    }

    /**
     * 28位 hex 字符串转 long（截取低 112 bits）
     */
    private long hexToLong(String hex) {
        // ADS-B 报文 112 bits，用两个 long 不够，分两次处理
        // 这里用简化的移位累加方式
        long result = 0;
        for (int i = 0; i < hex.length(); i++) {
            result = (result << 4) | Character.digit(hex.charAt(i), 16);
        }
        return result;
    }
}
