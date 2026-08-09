# ADS-B 报文生成与解码技术详解

> 面向飞行器监视的安全可信 ADS-B 仿真系统 — 核心技术文档  
> 熊康 · 南京航空航天大学 · 2026.07.25

---

## 目录

1. [ADS-B 报文结构](#1-ads-b-报文结构)
2. [报文生成流程（编码）](#2-报文生成流程编码)
3. [报文解码流程](#3-报文解码流程)
4. [CRC-24 校验原理与实现](#4-crc-24-校验原理与实现)
5. [CPR 紧凑位置报告编解码](#5-cpr-紧凑位置报告编解码)
6. [高度编码](#6-高度编码)
7. [飞机识别（呼号）编码](#7-飞机识别呼号编码)
8. [速度编码](#8-速度编码)
9. [Type Code 报文类型分发](#9-type-code-报文类型分发)
10. [代码文件索引](#10-代码文件索引)

---

## 1. ADS-B 报文结构

### 1.1 28 位 Hex 报文布局

ADS-B 报文本质上是 112 比特的二进制数据，以 28 位十六进制字符串表示。

```
8D 6F922D 58BF17099F3CCFFEAE 1F2A8C
│  │      │                  │
│  │      │                  └── PI  (6 hex = 24 bit)  CRC-24 校验码
│  │      └── ME  (14 hex = 56 bit)  数据域——高度、位置、速度
│  └── ICAO (6 hex = 24 bit)         飞机唯一标识
└── DF+CA (2 hex = 8 bit)            下行格式 + 能力字段
```

### 1.2 字段定义

| 字段 | 长度 | 位置 | 说明 |
|------|------|------|------|
| DF | 5 bit | bit 1-5 | Downlink Format，ADS-B 固定为 17 (10001) |
| CA | 3 bit | bit 6-8 | Capability，能力字段 |
| ICAO | 24 bit | bit 9-32 | 飞机 ICAO 24 位地址，全球唯一 |
| ME | 56 bit | bit 33-88 | 数据域，按 TC 类型不同而有不同格式 |
| PI | 24 bit | bit 89-112 | CRC-24 校验码 |

### 1.3 ME 数据域细分（TC=11 空中位置）

```
ME = TC(5) + SS(2) + SAF(1) + ALT(12) + T(1) + F(1) + CPR_LAT(17) + CPR_LON(17)
   = 5 + 2 + 1 + 12 + 1 + 1 + 17 + 17 = 56 bit
```

| 子字段 | 长度 | 说明 |
|--------|------|------|
| TC | 5 bit | Type Code，11 = 空中位置（气压高度）|
| SS | 2 bit | Surveillance Status，监视状态 |
| SAF | 1 bit | Single Antenna Flag |
| ALT | 12 bit | 气压高度编码 |
| T | 1 bit | Time flag |
| F | 1 bit | CPR 奇偶标志（0=偶帧, 1=奇帧）|
| CPR_LAT | 17 bit | 紧凑位置报告 — 纬度 |
| CPR_LON | 17 bit | 紧凑位置报告 — 经度 |

---

## 2. 报文生成流程（编码）

### 2.1 入口方法

```java
// SimulationService.java → generatePositionHex(Aircraft ac)
```

### 2.2 第 1 步：DF + ICAO（前 8 hex）

```java
sb.append("8D");           // DF=17 (10001), CA=0 (000) → 10001000 = 0x8D
sb.append(ac.getIcao());   // 6 hex ICAO 地址，如 "6F922D"
```

DF=17 的十六进制计算：

```
DF=17  = 二进制 10001
CA=0   = 二进制 000
拼接   = 10001 000 = 0x8D（16进制）
```

### 2.3 第 2 步：编码 ME 数据域（14 hex = 56 bit）

```java
int tc = 11;     // 空中位置(气压高度)
int ss = 0;      // 监视状态
int saf = 0;     // 单天线标志

// 高度编码：Q=1, 25ft 增量
int altRaw = (int) ((ac.getAltitude() + 1000) / 25);
altRaw = Math.max(0, Math.min(0xFFF, altRaw));

int t = 1;                          // Time flag
int f = RNG.nextInt(2);             // CPR 奇/偶帧标志

// CPR 编码
int cprLat = (int) ((ac.getLatitude() - Math.floor(ac.getLatitude())) * 131072);
int cprLon = (int) ((ac.getLongitude() - Math.floor(ac.getLongitude())) * 131072);

// 拼接 56 bit
long me = 0;
me = (me << 5)  | (tc & 0x1F);      // TC
me = (me << 2)  | (ss & 0x03);      // SS
me = (me << 1)  | (saf & 0x01);     // SAF
me = (me << 12) | (altRaw & 0xFFF); // ALT
me = (me << 1)  | (t & 0x01);       // T
me = (me << 1)  | (f & 0x01);       // F
me = (me << 17) | (cprLat & 0x1FFFF); // CPR_LAT
me = (me << 17) | (cprLon & 0x1FFFF); // CPR_LON

sb.append(String.format("%014X", me));
```

### 2.4 第 3 步：生成 CRC-24

```java
String body = sb.toString();              // 22 hex (DF+CA + ICAO + ME)
sb.append(crcService.generate(body));     // 附加 6 hex PI 字段
```

### 2.5 第 4 步：完成

```java
return sb.toString().toUpperCase();  // 28 hex 完整报文
```

### 2.6 编码示意图

```
┌────┬──────────┬────────────────────────────────┬──────────┐
│ DF │  ICAO    │         ME (数据域)             │ PI(CRC) │
│ 1B │  3B      │            7B                  │   3B    │
├────┼──────────┼────────────────────────────────┼──────────┤
│ 8D │ 6F922D   │ 58BF17099F3CCFFEAE            │ 1F2A8C   │
└────┴──────────┴────────────────────────────────┴──────────┘
                             ↓
    ME = TC(5) SS(2) SAF(1) ALT(12) T(1) F(1) LAT(17) LON(17)
       = 01011  00    0      高度    1   0/1  CPR_LAT CPR_LON
```

---

## 3. 报文解码流程

### 3.1 入口方法

```java
// AdsbDecoderService.java → parse(String hexMsg, long timestamp)
```

### 3.2 解码步骤

```
输入: 28 hex 报文
  │
  ├─ ① 校验: 长度==28? 全是合法 hex 字符?
  │
  ├─ ② CRC 校验: crcService.calculate(hex) → 余数==0?
  │
  ├─ ③ 提取 DF: hex[0] 前5 bit
  ├─ ④ 提取 ICAO: hex[2..7] 6 位
  ├─ ⑤ 提取 TC: bit 33-37 (5 bit)
  │
  └─ ⑥ 按 TC 分发:
       TC 1-4   → decodeIdentification()     飞机识别（呼号 + 类别）
       TC 5-8   → decodeSurfacePosition()    地面位置
       TC 9-18  → decodeAirbornePosition()   空中位置（气压高度）
       TC 19    → decodeVelocity()           空中速度
       TC 20-22 → decodeAirbornePosition()   空中位置（GNSS高度）
       TC 28    → 扩展 Squitter
       TC 29    → 目标状态
       TC 31    → 飞机运行状态
```

### 3.3 hex → 128 bit 转换

```java
private int[] hexToBits128(String hex) {
    int[] bits = new int[128];
    for (int i = 0; i < hex.length(); i++) {
        int nibble = Character.digit(hex.charAt(i), 16);
        for (int j = 0; j < 4; j++) {
            bits[i * 4 + j] = (nibble >> (3 - j)) & 1;
        }
    }
    return bits;
}
// "8D..." → [1,0,0,0, 1,1,0,1, ...]
```

### 3.4 提取指定位段

```java
private int bitsFromHex(String hex, int bitStart, int bitCount) {
    int[] bits = hexToBits128(hex);
    int val = 0;
    for (int i = bitStart; i < bitStart + bitCount; i++) {
        val = (val << 1) | bits[i];
    }
    return val;
}
```

---

## 4. CRC-24 校验原理与实现

### 4.1 原理

CRC（Cyclic Redundancy Check）通过**模二除法**检测数据传输错误：

- **发送端**：报文体 ÷ 生成多项式 → 余数填入 PI 字段
- **接收端**：完整报文 ÷ 同一个多项式 → 余数为 0 则通过

### 4.2 模二除法

模二除法用 **XOR（异或）** 代替减法：

```
XOR 规则: 相同得 0, 不同得 1
  1 XOR 1 = 0
  0 XOR 0 = 0
  1 XOR 0 = 1
  0 XOR 1 = 1
```

模二除法过程（简化示意）：

```
   报文:     1 0 1 0 0 1 0 0
   除数:     1 1 0 1
   ─────────────────────────
   1 0 1 0 0 1 0 0
   1 1 0 1          ← 对齐 XOR
   ─────────
   0 1 1 1 0 1 0 0
     1 1 0 1        ← 对齐 XOR
     ───────
     0 0 1 1 0 0 0
         1 1 0 1    ← 对齐 XOR
         ───────
         0 0 1 0 1 0
             1 1 0 1 ← 对齐 XOR
             ───────
             0 1 1 1 ← 余数 = CRC 值
```

### 4.3 ADS-B 的 CRC-24

- **生成多项式**: `G(x) = x²⁴ + x²³ + x²² + x²¹ + x²⁰ + x¹⁹ + x¹⁸ + x¹⁷ + x¹⁶ + x¹⁵ + x¹⁴ + x¹³ + x¹² + x¹⁰ + x³ + 1`
- **16 进制表示**: `0xFFFA0480`（25 位）
- **代码中**: `GENERATOR = 0x1FFF409`（BigInteger 去掉了最高位的 1）

### 4.4 代码实现

```java
// CrcService.java
@Service
public class CrcService {

    // 生成多项式（25 位：24 位系数 + 隐含最高位）
    private static final BigInteger GENERATOR = new BigInteger("1FFF409", 16);

    /**
     * 为前 22 hex（88 bit 报文体）生成 6 hex CRC
     */
    public String generate(String body) {
        // ① 报文体末尾补 6 个 0（预留 CRC 位置）
        String hexWithZeroParity = body + "000000";
        // ② 模二除法算余数
        long remainder = crcRemainder(hexWithZeroParity);
        // ③ 余数即 CRC 值
        return String.format("%06X", remainder);
    }

    /**
     * 校验完整报文
     */
    public boolean isValid(String hexMsg) {
        return calculate(hexMsg) == 0;
    }

    /**
     * 核心算法：逐位 XOR 模二除法
     */
    private long crcRemainder(String hex28) {
        BigInteger dividend = new BigInteger(hex28, 16);  // 112 bit
        // 从最高位(111)到第 24 位，逐位检查
        for (int i = 111; i >= 24; i--) {
            if (dividend.testBit(i)) {
                // 当前位是 1 → 对齐 GENERATOR 做 XOR
                dividend = dividend.xor(GENERATOR.shiftLeft(i - 24));
            }
        }
        // 取低 24 bit = 余数
        return dividend.longValue() & 0xFFFFFFL;
    }
}
```

### 4.5 为什么使用 BigInteger

```java
// ❌ 错误：long 只有 64 位，装不下 112 位的 ADS-B 报文
long dividend = Long.parseLong(hex28, 16); // 溢出！

// ✅ 正确：BigInteger 可以处理任意长度的二进制数
BigInteger dividend = new BigInteger(hex28, 16); // 112 位，完美
```

### 4.6 CRC 攻击检测

当攻击者篡改报文但不知道正确的 CRC 值时，接收端 CRC 余数 ≠ 0，立即触发告警。

```java
// triggerAttack("crc") → 修改 PI 字段的最后 6 位 hex
// → crcService.isValid() → false
// → SecurityCheckService → "CRC校验失败" (红色)
```

---

## 5. CPR 紧凑位置报告编解码

### 5.1 为什么需要 CPR

直接传输完整经纬度需要很多比特。CPR 的核心思想：

> **只传输小数部分，整数部分由接收方根据上下文推算。**

```
纬度 = 32.4567°
整数部分 32 → 省略（接收方根据上次位置推算）
小数部分 0.4567 × 131072 = 59840 → 用 17 bit 传到空中
```

### 5.2 奇偶帧配对

接收方只收到小数部分，如何确定整数部分是 32 还是 33 还是 34？

**答案：需要收到两种帧 — 偶帧和奇帧**，它们用不同的除数，交叉求解得到唯一解。

| 帧类型 | 纬度 Zone 大小 | 经度 Zone 大小 |
|--------|---------------|---------------|
| 偶帧 | 360° / 60 = 6° | 360° / (NL-1) |
| 奇帧 | 360° / 59 ≈ 6.102° | 360° / NL |

因为 60 和 59 互质，两个 zone 系统在全球范围内的唯一交点就是飞机的真实位置。

### 5.3 缓存机制

```java
// CprDecoderService.java
// ICAO → 偶帧缓存
private final ConcurrentHashMap<String, CprFrame> evenFrames = new ConcurrentHashMap<>();
// ICAO → 奇帧缓存
private final ConcurrentHashMap<String, CprFrame> oddFrames = new ConcurrentHashMap<>();

public void storeFrame(String icao, String hex, boolean isOdd, long ts, int lat, int lon) {
    CprFrame frame = new CprFrame(hex, ts, lat, lon);
    if (isOdd) oddFrames.put(icao, frame);
    else       evenFrames.put(icao, frame);
}

public double[] tryDecodeAirborne(String icao) {
    CprFrame even = evenFrames.get(icao);
    CprFrame odd  = oddFrames.get(icao);
    if (even == null || odd == null) return null; // 缺一帧，不解码
    return cprDecode(...);
}
```

### 5.4 全局解码算法

```java
private double[] cprDecode(int latEven, int lonEven, int latOdd, int lonOdd, ...) {
    // ===== 纬度解码 =====
    double dLatEven = 360.0 / 60.0;  // 偶帧：6°
    double dLatOdd  = 360.0 / 59.0;  // 奇帧：~6.102°

    // 纬度索引：交叉求解
    double j = Math.floor(
        59.0 * latEven / 131072.0 - 60.0 * latOdd / 131072.0 + 0.5
    );

    double lat = dLat * (mod(j, nz) + cprLat / 131072.0);

    // ===== 经度解码 =====
    int nl = computeNL(lat);  // 当前纬度带的经度区数

    double m = Math.floor(
        lonEven / 131072.0 * (nl - 1) - lonOdd / 131072.0 * nl + 0.5
    );

    double lon = dLon * (mod(m, nl) + cprLon / 131072.0);

    return new double[]{lat, lon};
}
```

### 5.5 NL 函数：纬度 → 经度区数

纬度越高，经度带越少。赤道附近 NL≈59，两极 NL=1。

```java
private int computeNL(double lat) {
    if (lat == 0) return 59;
    if (Math.abs(lat) >= 87.0) return 1;
    double nz = 15.0;
    return (int) Math.floor(
        2.0 * Math.PI / Math.acos(
            1.0 - (1.0 - Math.cos(Math.PI / (2.0 * nz)))
            / (Math.cos(Math.toRadians(Math.abs(lat)))
             * Math.cos(Math.toRadians(Math.abs(lat))))
        )
    );
}
```

### 5.6 参考位置解码（单帧）

当无法获得奇偶配对时，用已知的参考位置（如飞机上次位置）解码单帧：

```java
public double[] decodeWithReference(String hex, double latRef, double lonRef) {
    // 用参考位置的整数度 + CPR 小数部分 = 精确位置
    int j = (int) Math.floor(latRef / dLat)
          + (int) Math.floor(0.5 + (latRef % dLat) / dLat - cprLat / 131072.0);
    double lat = dLat * (j + cprLat / 131072.0);
    // ... 同理计算经度
}
```

---

## 6. 高度编码

### 6.1 编码（发送端）

```java
// Q=1 模式，25ft 精度
int altRaw = (int) ((ac.getAltitude() + 1000) / 25);
// 12 bit 范围: 0 - 4095
// 可表示: -1000ft ~ 101375ft
```

### 6.2 解码（接收端）

```java
private double decodeAltitude(String hex) {
    int[] bits = hexToBits128(hex);
    int altCode = bitsFromHex(hex, 40, 12);
    int qBit = bits[47];

    if (qBit == 1) {
        // Q=1: 25ft 增量
        return (encoded × 25 - 1000.0);
    } else {
        // Q=0: 100ft 增量 (Gillham 编码，简化处理)
        return (altCode × 100 - 1000.0);
    }
}
```

### 6.3 实例

```
飞机高度 30000 ft:
  编码: (30000 + 1000) / 25 = 1240 = 0x4D8（12 bit）
  解码: 1240 × 25 - 1000 = 30000 ft ✓
```

---

## 7. 飞机识别（呼号）编码

### 7.1 TC 1-4 报文

每 6 bit 编码一个字符，8 组共 48 bit：

```java
private char callsignChar(int code) {
    if (code >= 1  && code <= 26) return (char)('A' + code - 1);  // A-Z
    if (code >= 48 && code <= 57) return (char)('0' + code - 48); // 0-9
    if (code == 32) return ' ';                                    // 空格
    return '\0';
}
```

### 7.2 示例

```
000001 000010 000011 110010 110011 110001 110010 110100
  A=1    B=2    C=3    2      3      1      2      4

→ "ABC23124"
→ 去掉尾部空格 → "CCA23124"（一个典型的中国国航航班呼号）
```

---

## 8. 速度编码

### 8.1 TC 19 报文

速度分为**东西分量**和**南北分量**分别编码：

```java
int ewDir = bits[45];  // 0=东, 1=西
int nsDir = bits[56];  // 0=北, 1=南

int ewVel = bitsFromHex(hex, 46, 10);  // 10 bit 东西速度
int nsVel = bitsFromHex(hex, 57, 10);  // 10 bit 南北速度

double vx = (ewDir == 0 ? 1 : -1) * (ewVel - 1);
double vy = (nsDir == 0 ? 1 : -1) * (nsVel - 1);

double speed = Math.sqrt(vx * vx + vy * vy);     // 地速
double track = Math.toDegrees(Math.atan2(vx, vy)); // 航迹角
```

### 8.2 垂直速率

```java
int vrSrc = bits[68];      // 来源
int vrVal = bitsFromHex(hex, 69, 9); // 9 bit
int vrSign = (vrVal & 0x100) >> 8;  // 符号位
int vrMag = vrVal & 0xFF;           // 幅度

double vr = (vrSign == 0 ? 1 : -1) * (vrMag - 1) * 64.0; // ft/min
```

---

## 9. Type Code 报文类型分发

| TC 范围 | 消息类型 | 解码方法 | 说明 |
|---------|---------|---------|------|
| 1-4 | 飞机识别 | `decodeIdentification()` | 呼号 + 飞机类别 |
| 5-8 | 地面位置 | `decodeSurfacePosition()` | 滑行/地面移动 |
| 9-18 | 空中位置(气压) | `decodeAirbornePosition()` | 最常用，含高度 |
| 19 | 空中速度 | `decodeVelocity()` | 地速 + 航迹角 + 垂直速率 |
| 20-22 | 空中位置(GNSS) | `decodeAirbornePosition()` | GNSS 高度 |
| 28 | 扩展 Squitter | — | 预留 |
| 29 | 目标状态 | — | 预留 |
| 31 | 运行状态 | `decodeVersion()` | ADS-B 版本号 |

---

## 10. 代码文件索引

| 文件 | 路径 | 职责 |
|------|------|------|
| SimulationService | `backend/.../service/SimulationService.java` | 报文生成（`generatePositionHex()`）|
| AdsbDecoderService | `backend/.../service/AdsbDecoderService.java` | 报文解码总入口，TC 分发 |
| CprDecoderService | `backend/.../service/CprDecoderService.java` | CPR 奇偶帧配对全局解码 |
| CrcService | `backend/.../service/CrcService.java` | CRC-24 生成与校验 |
| AdsbParseResult | `backend/.../model/AdsbParseResult.java` | 解码结果 DTO |
| SecurityCheckService | `backend/.../service/SecurityCheckService.java` | 安全检测（CRC 失败即告警）|

---

> **核心总结：**  
> 生成端（SimulationService）将飞机的飞行参数按 ADS-B 标准编码为 28 hex 报文。  
> 解码端（AdsbDecoderService + CprDecoderService + CrcService）按 DO-260B 标准逐字段解析。  
> 编解码使用同一套算法（CRC-24、CPR、Q-bit 高度），保证仿真系统与真实 ADS-B 设备的兼容性。
