package com.offermate.modules.resume.exception;

import com.offermate.common.exception.BizException;

/**
 * 分片大小非法（非末片小于 5MB）。
 *
 * 单独定义这个类型，是因为它和「分片缺失」的处理方式完全不同：
 *   · 缺分片  → 正常业务分支，任务保留，前端补传那几片就行；
 *   · 大小非法 → 切片规则本身错了，而切片大小是「整个文件」的属性，
 *               改规则之后所有分片的边界都会变，只能整个重传 → 任务必须作废。
 */
public class PartSizeInvalidException extends BizException {

    public PartSizeInvalidException(String message) {
        super(message);
    }
}
