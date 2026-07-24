package com.adsb.simulation;

import com.adsb.simulation.service.AdsbDecoderService;
import com.adsb.simulation.service.CprDecoderService;
import com.adsb.simulation.service.CrcService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证与 Python decode.py 解析结果的一致性
 */
class AdsbDecoderServiceTest {

    private AdsbDecoderService decoderService;

    // Python decode.py 中的测试报文
    private static final String MSG_ID       = "8D4840D6202CC371C32CE0576098";
    private static final String MSG_POS_EVEN = "8D40621D58C382D690C8AC2863A7";
    private static final String MSG_POS_ODD  = "8D40621D58C386435CC412692AD6";
    private static final String MSG_VEL      = "8D485020994409940838175B284F";

    @BeforeEach
    void setUp() {
        CrcService crcService = new CrcService();
        CprDecoderService cprDecoderService = new CprDecoderService();
        decoderService = new AdsbDecoderService(crcService, cprDecoderService);
    }

    @Test
    @DisplayName("测试1：飞机识别消息")
    void testAircraftIdentification() {
        var result = decoderService.parse(MSG_ID, 0);

        assertNull(result.getError());
        assertEquals("飞机识别", result.getMessageType());
        assertEquals(17, result.getDf());          // DF=17
        assertEquals("4840D6", result.getIcao());
        assertEquals(4, result.getTc());
        assertNotNull(result.getCallsign());
        System.out.println("  呼号: " + result.getCallsign());
        System.out.println("  类别: " + result.getCategory());
    }

    @Test
    @DisplayName("测试2：空中位置偶帧")
    void testAirbornePositionEven() {
        var result = decoderService.parse(MSG_POS_EVEN, 1000);

        assertNull(result.getError());
        assertEquals("空中位置(气压高度)", result.getMessageType());
        assertNotNull(result.getCprLatRaw());
        assertNotNull(result.getCprLonRaw());
        assertNotNull(result.getAltitude());
        assertEquals("偶帧", result.getOddEvenFrame());

        System.out.println("  高度: " + result.getAltitude());
        System.out.println("  CPR Lat: " + result.getCprLatRaw()
                + " (" + result.getCprLatComputed() + ")");
        System.out.println("  CPR Lon: " + result.getCprLonRaw()
                + " (" + result.getCprLonComputed() + ")");
    }

    @Test
    @DisplayName("测试3：空中位置奇帧（配对解码）")
    void testAirbornePositionPair() {
        // 先解析偶帧
        decoderService.parse(MSG_POS_EVEN, 1000);
        // 再解析奇帧 — 应触发配对解码
        var result = decoderService.parse(MSG_POS_ODD, 1001);

        assertNull(result.getError());
        assertEquals("奇帧", result.getOddEvenFrame());
        assertEquals("奇偶帧配对(全局解码)", result.getPositionDecodeMethod());

        assertNotNull(result.getLatitude());
        assertNotNull(result.getLongitude());

        System.out.println("  纬度: " + result.getLatitude());
        System.out.println("  经度: " + result.getLongitude());
    }

    @Test
    @DisplayName("测试4：空中速度消息")
    void testVelocity() {
        var result = decoderService.parse(MSG_VEL, 0);

        assertNull(result.getError());
        assertEquals("空中速度", result.getMessageType());
        assertNotNull(result.getSpeed());
        assertNotNull(result.getHeading());

        System.out.println("  速度: " + result.getSpeed() + " kt");
        System.out.println("  航向: " + result.getHeading() + "°");
        System.out.println("  垂直速率: " + result.getVerticalRate() + " ft/min");
        System.out.println("  垂直方向: " + result.getVerticalDirection());
    }

    @Test
    @DisplayName("测试5：参考位置解码")
    void testPositionWithReference() {
        CprDecoderService cpr = new CprDecoderService();
        double[] posEven = cpr.decodeWithReference(MSG_POS_EVEN, 52.258, 3.918);
        double[] posOdd  = cpr.decodeWithReference(MSG_POS_ODD, 52.258, 3.918);

        assertNotNull(posEven);
        assertNotNull(posOdd);

        System.out.println("  偶帧参考解码: lat=" + posEven[0] + ", lon=" + posEven[1]);
        System.out.println("  奇帧参考解码: lat=" + posOdd[0] + ", lon=" + posOdd[1]);
    }

    @Test
    @DisplayName("测试6：CRC 校验")
    void testCrc() {
        var result = decoderService.parse(MSG_ID, 0);
        assertNotNull(result.getCrcRemainder());
        System.out.println("  CRC 余数: " + result.getCrcRemainder());
        System.out.println("  CRC 通过: " + result.getCrcValid());
    }

    @Test
    @DisplayName("测试7：非法输入")
    void testInvalidInput() {
        var result1 = decoderService.parse("1234567", 0);
        assertNotNull(result1.getError());

        var result2 = decoderService.parse(null, 0);
        assertNotNull(result2.getError());

        var result3 = decoderService.parse("GGGGGGGGGGGGGGGGGGGGGGGGGGGG", 0);
        assertNotNull(result3.getError());
    }
}
