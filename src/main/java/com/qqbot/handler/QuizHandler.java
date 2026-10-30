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
 * 群聊：@我 答题 <答案>（抢答）/ @我 今日一题（看题）
 * 私聊：今日一题（看题）；答题请去群里才有抢答气氛
 * <p>
 * 注意：新指令要同步到 AiHandler 的排除名单，否则 AI 会对这些词再回一条。
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
            bot.sendPrivateMsg(event.getUserId(), quizService.todayQuestionText(), false);
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
            bot.sendGroupMsg(event.getGroupId(), quizService.todayQuestionText(), false);
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

            bot.sendGroupMsg(event.getGroupId(), quizService.submitAnswer(event.getUserId(), answer), false);
        }
        return MESSAGE_IGNORE;
    }
}
