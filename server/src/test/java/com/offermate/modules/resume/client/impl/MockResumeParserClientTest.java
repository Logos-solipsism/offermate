package com.offermate.modules.resume.client.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可注入故障的解析实现：压测里"注入 30% 失败率"靠的就是它，
 * 所以它的故障注入能力必须有测试，否则压测结论站不住。
 */
class MockResumeParserClientTest {

    private MockResumeParserClient client(double failRate, long latencyMillis) {
        MockResumeParserClient client = new MockResumeParserClient();
        ReflectionTestUtils.setField(client, "failRate", failRate);
        ReflectionTestUtils.setField(client, "latencyMillis", latencyMillis);
        return client;
    }

    @Test
    @DisplayName("失败率设为 1 时必定抛出异常（故障注入生效）")
    void alwaysFailWhenFailRateIsOne() {
        MockResumeParserClient client = client(1.0, 0);
        assertThrows(IllegalStateException.class, () -> client.parse("resume/1/demo.pdf"));
    }

    @Test
    @DisplayName("失败率设为 0 时正常返回结构化字段")
    void parseReturnsStructuredFields() {
        Map<String, Object> parsed = client(0.0, 0).parse("resume/1/demo.pdf");
        assertEquals("张三", parsed.get("name"));
        assertEquals("本科", parsed.get("education"));
        assertNotNull(parsed.get("skills"));
        assertEquals("resume/1/demo.pdf", parsed.get("sourceObject"));
    }

    @Test
    @DisplayName("模拟耗时按配置生效")
    void latencyIsApplied() {
        long start = System.currentTimeMillis();
        client(0.0, 120).parse("resume/1/demo.pdf");
        long cost = System.currentTimeMillis() - start;
        assertTrue(cost >= 100, "应当至少耗时 100ms，实际 " + cost + "ms");
    }
}
