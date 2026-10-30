package com.qqbot.scheduler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.qqbot.service.QuizService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日一题定时推送。
 * cron 含义：秒 分 时 日 月 周 → 每天 8:00:00 触发。
 * 前提：启动类上要有 @EnableScheduling，定时任务才会生效
 */
@Component
public class DailyQuizScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyQuizScheduler.class);

    @Resource
    private QuizService quizService;

    @Resource
    private BotContainer botContainer;

    // 推送目标群号，application.yml 里配置；<= 0 视为未配置，跳过推送
    @Value("${quiz.group-id:0}")
    private long groupId;

    @Scheduled(cron = "0 0 8 * * ?")
    public void pushDailyQuestion() {
        if (groupId <= 0) {
            log.warn("quiz.group-id 未配置，跳过每日一题推送");
            return;
        }

        String text = quizService.pushDailyQuestion();

        // 调度线程不在消息事件里，用 BotContainer 拿常驻 Bot 实例。
        // 单号机器人就一个，取第一个即可
        Bot bot = botContainer.robots.isEmpty() ? null : botContainer.robots.values().iterator().next();
        if (bot == null) {
            // 推送失败要把今天的题还回题库，不然会被白白消耗掉
            quizService.resetToday();
            log.warn("每日一题推送失败：机器人不在线");
            return;
        }

        bot.sendGroupMsg(groupId, text, false);
        log.info("每日一题已推送到群 {}", groupId);
    }
}
