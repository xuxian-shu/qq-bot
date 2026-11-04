package com.qqbot.exception;

/**
 * 任务执行超时：由看门狗主动中断任务后抛出。
 * 继承 BusinessException，让 Handler 既能单独处理超时，也能统一按业务异常兜底。
 */
public class BusinessTimeoutException extends BusinessException {

    public BusinessTimeoutException(String message) {
        super(message);
    }

    public BusinessTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}