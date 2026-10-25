import pyModeS as pms


def hex2bits(hex_str):
    """将十六进制字符串转为比特列表"""
    bits = []
    for c in hex_str:
        nibble = bin(int(c, 16))[2:].zfill(4)
        bits.extend([int(b) for b in nibble])
    return bits


def extract_cpr(msg):
    """从ADS-B消息中提取CPR值"""
    bits = hex2bits(msg)
    # bit 54 (index 53): F标志 (0=even, 1=odd)
    # bits 55-71 (index 54-70): CPR-LAT (17 bits)
    # bits 72-88 (index 71-87): CPR-LON (17 bits)
    odd = bits[53]
    cpr_lat = 0
    for i in range(54, 71):
        cpr_lat = (cpr_lat << 1) | bits[i]
    cpr_lon = 0
    for i in range(71, 88):
        cpr_lon = (cpr_lon << 1) | bits[i]
    return odd, cpr_lat, cpr_lon


# ==================== 缓存帧用于配对解码位置 ====================
even_frames = {}  # {icao: (msg, timestamp)}
odd_frames = {}  # {icao: (msg, timestamp)}


def parse_message(msg: str, timestamp: int = 0):
    """
    解析单条 ADS-B 报文
    :param msg: 28位十六进制字符串 (112 bits)
    :param timestamp: 消息时间戳（用于位置配对）
    :return: 解析结果字典
    """
    result = {
        "原始报文": msg,
        "DF": None,
        "ICAO": None,
        "TC": None,
        "CRC校验": None,
        "类型": None,
    }

    # ---------- 1. 基础校验 ----------
    if len(msg) != 28:
        result["错误"] = "报文长度必须为28位十六进制"
        return result

    # CRC 校验: 返回0表示通过
    crc = pms.crc(msg, encode=False)
    result["CRC校验"] = (crc == 0)
    result["CRC余数"] = f"{crc:06X}"

    # ---------- 2. 基础字段 ----------
    df = pms.df(msg)
    icao = pms.icao(msg)
    tc = pms.adsb.typecode(msg)

    result["DF"] = df
    result["ICAO"] = icao
    result["TC"] = tc

    # ---------- 3. 根据 Type Code 解码 ----------
    if tc is None:
        result["类型"] = "非ADS-B消息"
        return result

    # TC 1-4: 飞机识别（呼号）
    if 1 <= tc <= 4:
        result["类型"] = "飞机识别"
        result["呼号"] = pms.adsb.callsign(msg)
        result["飞机类别"] = pms.adsb.category(msg)

    # TC 5-8: 地面位置
    elif 5 <= tc <= 8:
        result["类型"] = "地面位置"
        result["高度"] = 0  # 地面高度为0
        oe = pms.adsb.oe_flag(msg)
        result["奇偶帧"] = "偶帧" if oe == 0 else "奇帧"

        odd, cpr_lat, cpr_lon = extract_cpr(msg)
        result["CPR_LAT原始值"] = cpr_lat
        result["CPR_LON原始值"] = cpr_lon
        result["CPR_LAT计算值"] = round(cpr_lat / 131072, 5)
        result["CPR_LON计算值"] = round(cpr_lon / 131072, 5)

        # 缓存帧
        if oe == 0:
            even_frames[icao] = (msg, timestamp)
        else:
            odd_frames[icao] = (msg, timestamp)
        # 尝试配对解码
        _try_decode_position(icao, result, timestamp, is_surface=True)

    # TC 9-18: 空中位置（气压高度）
    elif 9 <= tc <= 18:
        result["类型"] = "空中位置(气压高度)"
        result["高度"] = pms.adsb.altitude(msg)
        result["高度来源"] = "气压高度"

        oe = pms.adsb.oe_flag(msg)
        result["奇偶帧"] = "偶帧" if oe == 0 else "奇帧"

        odd, cpr_lat, cpr_lon = extract_cpr(msg)
        result["CPR_LAT原始值"] = cpr_lat
        result["CPR_LON原始值"] = cpr_lon
        result["CPR_LAT计算值"] = round(cpr_lat / 131072, 5)
        result["CPR_LON计算值"] = round(cpr_lon / 131072, 5)

        # 缓存帧
        if oe == 0:
            even_frames[icao] = (msg, timestamp)
        else:
            odd_frames[icao] = (msg, timestamp)
        # 尝试配对解码
        _try_decode_position(icao, result, timestamp, is_surface=False)

    # TC 19: 空中速度
    elif tc == 19:
        result["类型"] = "空中速度"
        try:
            spd, trk_or_hdg, vr, spd_type = pms.adsb.velocity(msg)
            result["速度"] = f"{spd} kt"
            result["航向/航迹角"] = f"{trk_or_hdg:.1f}°"
            result["垂直速率"] = f"{vr} ft/min"
            result["速度类型"] = spd_type  # 'GS' 地速 或 'AS' 空速
            result["垂直方向"] = "下降" if vr < 0 else "上升" if vr > 0 else "平飞"
            result["垂直速率绝对值"] = f"{abs(vr)} ft/min"
        except Exception as e:
            result["速度解码错误"] = str(e)

    # TC 20-22: 空中位置（GNSS高度）
    elif 20 <= tc <= 22:
        result["类型"] = "空中位置(GNSS高度)"
        result["高度"] = pms.adsb.altitude(msg)
        result["高度来源"] = "GNSS高度"
        oe = pms.adsb.oe_flag(msg)
        result["奇偶帧"] = "偶帧" if oe == 0 else "奇帧"

        odd, cpr_lat, cpr_lon = extract_cpr(msg)
        result["CPR_LAT原始值"] = cpr_lat
        result["CPR_LON原始值"] = cpr_lon

        if oe == 0:
            even_frames[icao] = (msg, timestamp)
        else:
            odd_frames[icao] = (msg, timestamp)
        _try_decode_position(icao, result, timestamp, is_surface=False)

    # TC 29: 目标状态
    elif tc == 29:
        result["类型"] = "目标状态和状态信息"

    # TC 31: 飞机运行状态
    elif tc == 31:
        result["类型"] = "飞机运行状态"
        result["ADS-B版本"] = pms.adsb.version(msg)

    else:
        result["类型"] = f"保留/未实现 (TC={tc})"

    return result


def _try_decode_position(icao, result, timestamp, is_surface=False):
    """尝试用奇偶帧配对解码位置"""
    if icao in even_frames and icao in odd_frames:
        msg_even, t_even = even_frames[icao]
        msg_odd, t_odd = odd_frames[icao]

        try:
            if is_surface:
                lat, lon = pms.adsb.surface_position(
                    msg_even, msg_odd, t_even, t_odd,
                    lat_ref=52.3086, lon_ref=4.7639
                )
            else:
                lat, lon = pms.adsb.airborne_position(
                    msg_even, msg_odd, t_even, t_odd
                )
            result["纬度"] = round(lat, 5)
            result["经度"] = round(lon, 5)
            result["位置解码方式"] = "奇偶帧配对(全局解码)"
        except Exception as e:
            result["位置解码错误"] = str(e)


def decode_with_reference(msg: str, lat_ref: float, lon_ref: float):
    """
    用参考位置解码单条位置报文（无需奇偶配对）
    :param msg: 28位十六进制字符串
    :param lat_ref: 参考纬度
    :param lon_ref: 参考经度
    :return: (纬度, 经度)
    """
    return pms.adsb.position_with_ref(msg, lat_ref, lon_ref)


# ==================== 测试 ====================
if __name__ == "__main__":
    msg_id = "8D4840D6202CC371C32CE0576098"  # 飞机识别
    msg_pos_even = "8D40621D58C382D690C8AC2863A7"  # 空中位置偶帧
    msg_pos_odd = "8D40621D58C386435CC412692AD6"  # 空中位置奇帧
    msg_vel = "8D485020994409940838175B284F"  # 空中速度

    print("=" * 70)
    print("【测试1】飞机识别消息")
    print("=" * 70)
    res1 = parse_message(msg_id)
    for k, v in res1.items():
        print(f"  {k}: {v}")

    print("\n" + "=" * 70)
    print("【测试2】空中位置偶帧")
    print("=" * 70)
    res2 = parse_message(msg_pos_even, timestamp=1000)
    for k, v in res2.items():
        print(f"  {k}: {v}")

    print("\n" + "=" * 70)
    print("【测试3】空中位置奇帧（配对解码）")
    print("=" * 70)
    res3 = parse_message(msg_pos_odd, timestamp=1001)
    for k, v in res3.items():
        print(f"  {k}: {v}")

    print("\n" + "=" * 70)
    print("【测试4】空中速度消息")
    print("=" * 70)
    res4 = parse_message(msg_vel)
    for k, v in res4.items():
        print(f"  {k}: {v}")

    print("\n" + "=" * 70)
    print("【测试5】单条位置用参考位置解码")
    print("=" * 70)
    lat, lon = decode_with_reference(msg_pos_even, 52.258, 3.918)
    print(f"  参考位置: 纬度=52.258, 经度=3.918")
    print(f"  偶帧解码: 纬度={lat:.5f}, 经度={lon:.5f}")

    lat2, lon2 = decode_with_reference(msg_pos_odd, 52.258, 3.918)
    print(f"  奇帧解码: 纬度={lat2:.5f}, 经度={lon2:.5f}")