package com.qqbot.exception;

/**
 * 业务异常：表示“可以预期、要原样告诉用户”的情况，
 * 比如线程池满了、参数不合法等，不打印堆栈也可以接受。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}