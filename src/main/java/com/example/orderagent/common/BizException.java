package com.example.orderagent.common;

/** 业务异常：带错误码，统一由 GlobalExceptionHandler 转成 ApiResult。 */
public class BizException extends RuntimeException {
    private final int code;

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public BizException(String msg) {
        this(400, msg);
    }

    public int getCode() {
        return code;
    }
}