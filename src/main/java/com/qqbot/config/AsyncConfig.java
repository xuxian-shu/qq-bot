package com.qqbot.config;

import com.qqbot.exception.BusinessException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 快慢业务隔离：
 * 签到、查积分、查天气等快业务继续走主线程；
 * AI 聊天、运势卡片这类吃 CPU / 内存的慢业务各走各的线程池。
 * <p>
 * 池子满了不走原生 RejectedExecutionException，而是抛 BusinessException，
 * Handler 在主线程同步接住后直接给用户降级提示。
 * <p>
 * 注意：这里用 ThreadPoolTaskExecutor（Spring 自带），没有引入任何 MQ / 调度中间件。
 */
@Configuration
public class AsyncConfig {

    public static final String AI_CHAT_EXECUTOR = "aiChatExecutor";
    public static final String IMAGE_EXECUTOR = "imageExecutor";

    /** AI 聊天：本地 Ollama 吃内存，线程数压到最小 */
    @Bean(AI_CHAT_EXECUTOR)
    public ThreadPoolTaskExecutor aiChatExecutor() {
        return buildExecutor("biz-ai-", 3, 5, 20);
    }

    /** 运势卡片：AWT 画图吃 CPU，队列给宽一点，避免用户多的时候直接被拒 */
    @Bean(IMAGE_EXECUTOR)
    public ThreadPoolTaskExecutor imageExecutor() {
        return buildExecutor("biz-img-", 3, 5, 30);
    }

    private ThreadPoolTaskExecutor buildExecutor(String threadNamePrefix,
                                                 int corePoolSize,
                                                 int maxPoolSize,
                                                 int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix(threadNamePrefix);

        // 排队满 + 线程数到顶时触发。抛业务异常，Handler 能同步捕获并回复用户
        executor.setRejectedExecutionHandler((task, pool) -> {
            throw new BusinessException("当前群内请求太火爆啦，请稍后再试~");
        });

        // 优雅停机：正在跑的 AI / 画图任务给 30 秒收尾，避免重启时用户收不到回复
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        // 不要在这里手动 initialize()，Spring 创建 Bean 时会调用 afterPropertiesSet()
        return executor;
    }
}