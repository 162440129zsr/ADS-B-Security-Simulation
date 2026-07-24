package com.adsb.simulation.controller;

import com.adsb.simulation.model.AdsbParseResult;
import com.adsb.simulation.service.CprDecoderService;
import com.adsb.simulation.service.AdsbDecoderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/adsb")
@RequiredArgsConstructor
public class AdsbController {

    private final AdsbDecoderService decoderService;
    private final CprDecoderService cprDecoderService;

    /**
     * 解析单条 ADS-B 报文
     */
    @PostMapping("/parse")
    public Map<String, Object> parse(@RequestBody Map<String, Object> body) {
        String hexMsg = (String) body.get("hexMsg");
        long timestamp = body.containsKey("timestamp")
                ? ((Number) body.get("timestamp")).longValue()
                : System.currentTimeMillis();

        AdsbParseResult result = decoderService.parse(hexMsg, timestamp);
        return result.toOrderedMap();
    }

    /**
     * 批量解析 ADS-B 报文
     */
    @PostMapping("/parse/batch")
    public List<Map<String, Object>> parseBatch(@RequestBody List<Map<String, Object>> messages) {
        List<Map<String, Object>> results = new ArrayList<>();
        long baseTime = System.currentTimeMillis();
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            String hexMsg = (String) msg.get("hexMsg");
            long timestamp = msg.containsKey("timestamp")
                    ? ((Number) msg.get("timestamp")).longValue()
                    : baseTime + i;
            results.add(decoderService.parse(hexMsg, timestamp).toOrderedMap());
        }
        return results;
    }

    /**
     * 用参考位置解码单条位置报文
     */
    @PostMapping("/parse/with-ref")
    public Map<String, Object> parseWithRef(@RequestBody Map<String, Object> body) {
        String hexMsg = (String) body.get("hexMsg");
        double latRef = ((Number) body.get("latRef")).doubleValue();
        double lonRef = ((Number) body.get("lonRef")).doubleValue();

        double[] pos = cprDecoderService.decodeWithReference(hexMsg, latRef, lonRef);
        return Map.of(
                "hexMsg", hexMsg,
                "latitude", pos[0],
                "longitude", pos[1],
                "latRef", latRef,
                "lonRef", lonRef
        );
    }
}
