package com.offermate.modules.resume.client;

import java.util.Map;

/**
 * 三方简历解析能力（真实项目里是 OCR / 解析服务 + 大模型结构化）。
 * 单独抽成接口是为了：
 *   1) 方便用 Mock 实现做"故障注入"，验证指数退避重试与死信兜底；
 *   2) 真实接入时只替换实现类，业务代码不动。
 */
public interface ResumeParserClient {

    /**
     * 解析简历，返回结构化字段（姓名、学历、技能、项目经历等）。
     *
     * @param objectKey MinIO 对象路径
     * @return 结构化结果
     */
    Map<String, Object> parse(String objectKey);
}
