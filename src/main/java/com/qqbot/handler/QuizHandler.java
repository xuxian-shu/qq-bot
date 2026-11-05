package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import com.qqbot.service.CooldownService;
import com.qqbot.service.QuizService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 每日一题插件。
 * 群聊：@我 答题 <答案>（抢答，仅限出题的那个群）/ @我 今日一题（看题）
 * 私聊：今日一题（看题）；答题请去群里才有抢答气氛
 * <p>
 * 指令词已登记在 CommandWords，AiHandler 排除名单与破冰黑名单自动生效。
 */
@Component
public class QuizHandler extends BotPlugin {

    @Resource
    private QuizService quizService;

    @Resource
    private CooldownService cooldownService;

    //私聊：只能看题
    @Override
    public int onPrivateMessage(Bot bot, PrivateMessageEvent event) {
        String msg = event.getMessage();
        if (msg == null) {
            return MESSAGE_IGNORE;
        }
        String text = msg.trim();
        if ("今日一题".equals(text)) {
            // 私聊查题 groupId 传 0：不做绑群校验，但已绑群的题被抢答前
            // 只回题面不回答案，抄不到跨群的答案
            bot.sendPrivateMsg(event.getUserId(), quizService.todayQuestionText(0), false);
        } else if (text.startsWith("答题")) {
            bot.sendPrivateMsg(event.getUserId(), "答题要在群里玩才有抢答气氛喵～\n去群里发「@我 答题 你的答案」", false);
        }
        return MESSAGE_IGNORE;
    }

    //群聊：抢答 / 看题
    @Override
    public int onGroupMessage(Bot bot, GroupMessageEvent event) {
        String msg = event.getMessage();
        if (msg == null) {
            return MESSAGE_IGNORE;
        }
        String atCode = "[CQ:at,qq=" + event.getSelfId() + "]";
        if (!msg.contains(atCode)) {
            return MESSAGE_IGNORE;
        }
        String cleanMsg = msg.replace(atCode, "").trim();

        if ("今日一题".equals(cleanMsg)) {
            bot.sendGroupMsg(event.getGroupId(), quizService.todayQuestionText(event.getGroupId()), false);
            return MESSAGE_IGNORE;
        }

        if (cleanMsg.startsWith("答题")) {
            String answer = cleanMsg.substring("答题".length()).trim();
            if (answer.isEmpty()) {
                bot.sendGroupMsg(event.getGroupId(), "格式：@我 答题 你的答案", false);
                return MESSAGE_IGNORE;
            }

            // 3 秒冷却，防止连发刷屏
            if (cooldownService.isCoolingDown("quiz", event.getUserId(), 3)) {
                bot.sendGroupMsg(event.getGroupId(), "操作太频繁啦，请 3 秒后再试！", false);
                return MESSAGE_IGNORE;
            }

            // 抢答必须发生在出题的群：本群群号交给 Service 做绑群校验和尝试计数
            bot.sendGroupMsg(event.getGroupId(),
                    quizService.submitAnswer(event.getUserId(), answer, event.getGroupId()), false);
        }
        return MESSAGE_IGNORE;
    }
}
