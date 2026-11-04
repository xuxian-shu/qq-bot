package com.qqbot.scheduler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.qqbot.service.BiliService;
import com.qqbot.service.BindInfo;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * B站 UP 主数据日报：每天 20:00 给所有绑定且被判定为 UP 主的人推一张数据卡片。
 * <p>
 * 推送到绑定时的来源：群绑定回群，私聊绑定回私聊。
 * 单个用户拉取失败只打日志，不影响其他人；风控导致数据全空则本期跳过
 * （由 BiliService.buildDailyReport 返回 null 实现）。
 */
@Component
public class BiliReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(BiliReportScheduler.class);

    @Resource
    private BiliService biliService;

    @Resource
    private BotContainer botContainer;

    @Value("${bili.report.enabled:true}")
    private boolean enabled;

    // cron：秒 分 时 日 月 周 → 每天 20:00:00
    @Scheduled(cron = "0 0 20 * * ?")
    public void pushDailyReports() {
        if (!enabled) {
            return;
        }
        Bot bot = botContainer.robots.isEmpty() ? null : botContainer.robots.values().iterator().next();
        if (bot == null) {
            log.warn("B站日报推送失败：机器人不在线");
            return;
        }

        int delivered = 0;
        for (BindInfo user : biliService.listBindings()) {
            try {
                String report = biliService.buildDailyReport(user);
                if (report == null) {
                    continue;
                }
                if (user.groupId() > 0) {
                    bot.sendGroupMsg(user.groupId(), report, false);
                } else {
                    bot.sendPrivateMsg(user.qq(), report, false);
                }
                delivered++;
            } catch (Exception e) {
                // 单个用户失败不中断，下一期继续
                log.warn("B站日报推送失败：qq={}, {}", user.qq(), e.getMessage());
            }
        }
        log.info("B站日报推送完成：共 {} 人", delivered);
    }
}