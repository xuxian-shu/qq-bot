package com.qqbot.utils;

import com.qqbot.exception.BusinessException;
import com.qqbot.exception.BusinessTimeoutException;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 轻量级任务看门狗：给任意任务套一个「最多允许跑 N 秒」的硬上限。
 * <p>
 * 实现只依赖 JDK 原生并发包：
 * 内部起一个单线程池执行任务，调用方通过 Future.get(timeout) 限时等待；
 * 超时后 cancel(true) 发中断信号，再抛 BusinessTimeoutException。
 * <p>
 * 使用示例：
 * <pre>
 * String reply = TimeLimitUtil.call("ai-chat", 10, () -> aiService.chat(msg, chatId));
 * </pre>
 * <p>
 * 注意：cancel(true) 只是给任务线程发中断标记，
 * sleep / wait / 可中断 IO（如 JDK HttpClient）会立即响应；
 * 像 OkHttp 的阻塞 socket 读不响应中断，必须在 HTTP 层再配 callTimeout，
 * 否则看门狗只能保证「调用方不再等」，不能让底层请求立刻停止。
 */
public final class TimeLimitUtil {

    private TimeLimitUtil() {
    }

    /**
     * 限时执行任务。
     *
     * @param bizName        业务名，用于线程命名和异常信息，建议用英文短名
     * @param timeoutSeconds 超时时间（秒）
     * @param task           要执行的任务
     * @return 任务的返回值
     * @throws BusinessTimeoutException 超时或被中断
     */
    public static <T> T call(String bizName, long timeoutSeconds, Callable<T> task) {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为空");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds 必须大于 0");
        }

        // 每个任务一个守护线程：正常结束/超时后线程立即退出，不会常驻
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "watchdog-" + bizName);
            thread.setDaemon(true);
            return thread;
        });

        Future<T> future = executor.submit(task);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 超时：先中断任务，再抛自定义异常，让 Handler 能给用户降级回复
            future.cancel(true);
            throw new BusinessTimeoutException(
                    bizName + " 执行超时（" + timeoutSeconds + " 秒）", e);
        } catch (InterruptedException e) {
            // 调用方线程自己被中断（例如应用停机），把中断状态还回去
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessTimeoutException(bizName + " 被中断", e);
        } catch (ExecutionException e) {
            // 任务本身抛了异常：原样抛出，别包一层让 Handler 判断不到
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new BusinessException(bizName + " 执行失败：" + cause.getMessage(), cause);
        } finally {
            // 关键：无论成功、超时还是异常都要关掉内部线程池，避免线程泄漏
            executor.shutdownNow();
        }
    }
}