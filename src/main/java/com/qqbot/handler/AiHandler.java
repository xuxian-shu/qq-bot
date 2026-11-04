package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import com.qqbot.exception.BusinessException;
import com.qqbot.exception.BusinessTimeoutException;
import com.qqbot.service.AiService;
import com.qqbot.service.CooldownService;
import com.qqbot.utils.TimeLimitUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import static com.qqbot.handler.BiliHandler.isBiliCommand;
import static com.qqbot.handler.FortuneHandler.isFortuneCommand;
import static com.qqbot.handler.HelpHandler.isHelpCommand;

@Component
public class AiHandler extends BotPlugin {

    private static final Logger log = LoggerFactory.getLogger(AiHandler.class);

    @Resource
    private AiService aiService;
    @Resource
    private CooldownService cooldownService;//注入冷却服务

    // AI 聊天专用池：和签到/查积分等快业务隔离，慢业务卡死也不影响主线程
    @Resource(name = "aiChatExecutor")
    private ThreadPoolTaskExecutor aiChatExecutor;

    //1.处理私聊消息
    @Override
    public int onPrivateMessage(Bot bot, PrivateMessageEvent event) {
        String msg = event.getMessage();
        long qq = event.getUserId();

        // 指令类消息（签到、hello、帮助、天气、积分、提醒、运势）不由 AI 处理，直接返回。
        // 各指令归各自的 Handler 管，不排除的话对应 Handler 回完后
        // AI 还会再回一条，用户收到双份回复。
        // 注意：这里必须 return，不能只把它们排除在冷却判断外面，
        // 否则后面照样会调用 aiService.chat()，AI 还是会插嘴。
        if ("签到".equals(msg) || "hello".equals(msg) || "天气".equals(msg) || msg.startsWith("天气 ")
                || "积分".equals(msg) || "积分排行".equals(msg)
                || msg.startsWith("提醒我") || msg.startsWith("答题") || "今日一题".equals(msg)
                || isFortuneCommand(msg) || isHelpCommand(msg) || isBiliCommand(msg)) {
            return MESSAGE_IGNORE;
        }

        //普通聊天才检查冷却：私聊，每人3秒
        if (cooldownService.isCoolingDown("ai", qq, 3)) {//如果在冷却中
            bot.sendPrivateMsg(qq, "本王还在冷却中，请3秒后再试", false);//发送提示消息给用户
            return MESSAGE_IGNORE;
        }

        // 慢业务丢线程池，主线程立刻返回，不阻塞其他消息
        submitAiChat(bot, 0, qq, msg, String.valueOf(qq));
        return MESSAGE_IGNORE;
    }

    //2.处理群聊消息
    @Override
    public int onGroupMessage(Bot bot, GroupMessageEvent event) {
        String msg = event.getMessage();//获取群聊消息内容
        long groupId = event.getGroupId();//获取群聊ID
        long qq = event.getUserId();//获取用户QQ号

        // 动态获取机器人自己的 QQ 号，不再写死
        String atCode = "[CQ:at,qq=" + event.getSelfId() + "]";

        //只要当消息包含了@机器人QQ号，才处理
        if (msg.contains(atCode)) {
            //把@机器人的代码删掉，只留用户消息
            String cleanMsg = msg.replace(atCode, "").trim();//删除@机器人的代码

            if (cleanMsg.isEmpty()) {//如果用户消息为空
                bot.sendGroupMsg(groupId, "神人，艾特我干嘛", false);//发送提示消息给群聊
                return MESSAGE_IGNORE;
            }

            // 指令类消息交给各自的 Handler：
            // 签到 → SignInHandler，点歌 → MusicHandler，帮助 → HelpHandler，
            // 积分 → PointsHandler，提醒 → ReminderHandler，天气 → WeatherHandler，
            // 运势 → FortuneHandler。
            // 这里必须 return，否则 AI 还会再回复一次。
            if ("签到".equals(cleanMsg) || "hello".equals(cleanMsg) || "积分".equals(cleanMsg)
                    || "积分排行".equals(cleanMsg) || cleanMsg.startsWith("点歌")
                    || cleanMsg.startsWith("提醒我") || "天气".equals(cleanMsg)
                    || cleanMsg.startsWith("天气 ") || cleanMsg.startsWith("答题")
                    || "今日一题".equals(cleanMsg) || isFortuneCommand(cleanMsg)
                    || isHelpCommand(cleanMsg) || isBiliCommand(cleanMsg)) {
                return MESSAGE_IGNORE;
            }

            //群聊，每人5秒冷却（防止刷屏）
            if (cooldownService.isCoolingDown("ai", qq, 5)) {//如果在冷却中
                bot.sendGroupMsg(groupId, "本王还在冷却中! 请5秒后再试", false);//发送提示消息给群聊
                return MESSAGE_IGNORE;
            }

            // 慢业务丢线程池，chatId 用「群号_QQ号」保持上下文隔离
            submitAiChat(bot, groupId, qq, cleanMsg, groupId + "_" + qq);
        }
        return MESSAGE_IGNORE;
    }

    /**
     * 把 AI 聊天提交到独立线程池。
     * <p>
     * 这里必须用 execute() 而不是 submit()：池子满时自定义拒绝策略抛出的
     * BusinessException 会同步抛回当前线程，能立刻给用户降级提示；
     * submit() 会把它包进 Future，异常就石沉大海了。
     * <p>
     * 注意：入参全部是基本类型，不要把 Shiro 的 event 带进异步线程。
     */
    private void submitAiChat(Bot bot, long groupId, long qq, String msg, String chatId) {
        try {
            aiChatExecutor.execute(() -> {
                try {
                    // 看门狗：本地 Ollama 超过 10 秒直接中断，防止任务卡死线程池
                    String reply = TimeLimitUtil.call("ai-chat", 10,
                            () -> aiService.chat(msg, chatId));
                    sendReply(bot, groupId, qq, reply);
                } catch (BusinessTimeoutException e) {
                    // 超时：只回超时提示，不再等 AI 的返回
                    log.warn("AI 聊天超时(10s)：qq={}, msg={}", qq, msg);
                    sendReply(bot, groupId, qq, "AI大脑烧糊了，回答超时啦~");
                } catch (Exception e) {
                    // 异步线程里抛异常没人接，必须自己兜底，否则用户永远等不到回复
                    log.error("AI 聊天任务执行失败：qq={}, msg={}", qq, msg, e);
                    sendReply(bot, groupId, qq, "本王脑袋卡壳了，稍后再试试喵~");
                }
            });
        } catch (BusinessException e) {
            // 线程池已满：主线程直接降级回复，不再调用 Ollama
            log.warn("AI 聊天线程池已满，降级提示：qq={}", qq);
            sendReply(bot, groupId, qq, e.getMessage());
        }
    }

    /** groupId > 0 走群聊，否则走私聊（与 FortuneHandler 约定一致） */
    private void sendReply(Bot bot, long groupId, long qq, String reply) {
        if (groupId > 0) {
            bot.sendGroupMsg(groupId, reply, false);
        } else {
            bot.sendPrivateMsg(qq, reply, false);
        }
    }
}
