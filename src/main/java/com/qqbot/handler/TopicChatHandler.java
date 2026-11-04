package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.qqbot.exception.BusinessException;
import com.qqbot.exception.BusinessTimeoutException;
import com.qqbot.moderation.SensitiveWordFilter;
import com.qqbot.service.AiService;
import com.qqbot.service.ChatActivityService;
import com.qqbot.service.CooldownService;
import com.qqbot.utils.TimeLimitUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 话题接茬：群里有人不带@地说「好无聊」「今天吃什么」这类触发词时，
 * 按 chance 概率把最近一段群聊日志喂给 AI，生成一句自然插话主动发到群里。
 * <p>
 * 不讨人嫌 / 不刷屏的四道防线：
 * ①概率抽稀——命中关键词也不一定有份，模拟真人"看心情接话"；
 * ②群 + 用户双重冷却（isCoolingDown 的 setIfAbsent 语义：判过即消耗额度）；
 * ③自身消息已由拦截器在分发入口全局阻断，本地再兜底判一次，
 *   保证就算机器人自己的插话命中关键词也不会自己接自己；
 * ④AI 产物先过一遍敏感词，宁可闭嘴也不自己违规。
 * <p>
 * 主动接话没人点名要你回：超时、失败、池满一律静默，不发道歉文案。
 * 慢操作全部走 aiChatExecutor，主线程只做轻量判断。
 */
@Component
public class TopicChatHandler extends BotPlugin {

    private static final Logger log = LoggerFactory.getLogger(TopicChatHandler.class);

    private static final Pattern CQ_CODE = Pattern.compile("\\[CQ:[^]]*]");

    // 指令类前缀：「提醒我 1小时后 吃什么」这种也含关键词，别去接
    private static final List<String> COMMAND_PREFIXES = List.of("提醒我", "点歌", "答题", "帮助", "指南", "菜单");

    @Value("${proactive.topic.enabled:true}")
    private boolean enabled;

    // 逗号分隔，例：好无聊啊,今天吃什么,有没有人打游戏
    @Value("${proactive.topic.keywords:}")
    private String keywordsRaw;

    @Value("${proactive.topic.chance:0.3}")
    private double chance;

    @Value("${proactive.topic.group-cooldown-seconds:3600}")
    private long groupCooldownSeconds;

    @Value("${proactive.topic.user-cooldown-seconds:600}")
    private long userCooldownSeconds;

    @Value("${proactive.topic.context-size:10}")
    private int contextSize;

    @Resource
    private AiService aiService;

    @Resource
    private CooldownService cooldownService;

    @Resource
    private ChatActivityService chatActivityService;

    @Resource
    private SensitiveWordFilter sensitiveWordFilter;

    // 复用 AI 聊天线程池：接茬和 @聊天同属慢业务，也必须隔离出主线程
    @Resource(name = "aiChatExecutor")
    private ThreadPoolTaskExecutor aiChatExecutor;

    @Override
    public int onGroupMessage(Bot bot, GroupMessageEvent event) {
        if (!enabled) {
            return MESSAGE_IGNORE;
        }
        String msg = event.getMessage();
        Long groupId = event.getGroupId();
        Long userId = event.getUserId();
        Long selfId = event.getSelfId();
        if (msg == null || groupId == null || userId == null) {
            return MESSAGE_IGNORE;
        }

        // 兜底自环防线：正常走不到这里（拦截器已全局阻断自身消息），
        // 但主动发言功能不能把安全性押在另一个类的配置上
        if (selfId != null && selfId.equals(userId)) {
            return MESSAGE_IGNORE;
        }
        // 带@的消息归 AiHandler 正经回复，这里不重复插嘴
        if (selfId != null && msg.contains("[CQ:at,qq=" + selfId + "]")) {
            return MESSAGE_IGNORE;
        }

        String text = CQ_CODE.matcher(msg).replaceAll("").trim();
        if (text.isEmpty() || !matchesKeyword(text) || isCommandLike(text)) {
            return MESSAGE_IGNORE;
        }
        // 概率抽稀：不是每次都接，接多了就不像人了
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return MESSAGE_IGNORE;
        }
        // 先判人再判群：单用户冷却更细，能挡住一个人刷关键词
        if (cooldownService.isCoolingDown("topic_u", userId, userCooldownSeconds)
                || cooldownService.isCoolingDown("topic_g", groupId, groupCooldownSeconds)) {
            return MESSAGE_IGNORE;
        }

        // 此刻本条消息已被拦截器写入日志，所以上下文最后一句就是它
        List<String> context = chatActivityService.getRecentMessages(groupId, contextSize);
        submitChimeIn(bot, groupId, text, context);
        return MESSAGE_IGNORE;
    }

    private boolean matchesKeyword(String text) {
        if (keywordsRaw == null || keywordsRaw.isBlank()) {
            return false;
        }
        for (String keyword : keywordsRaw.split(",")) {
            String trimmed = keyword.trim();
            if (!trimmed.isEmpty() && text.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCommandLike(String text) {
        for (String prefix : COMMAND_PREFIXES) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成并发送接话。入参全部是基本类型/String，不把 Shiro event 带进异步线程
     * （与 AiHandler.submitAiChat 同一约定）。
     */
    private void submitChimeIn(Bot bot, long groupId, String text, List<String> context) {
        String prompt = buildPrompt(text, context);
        try {
            aiChatExecutor.execute(() -> {
                try {
                    String reply = TimeLimitUtil.call("topic-chime-in", 10,
                            () -> aiService.chat(prompt, groupId + "_topic"));
                    if (reply == null || reply.isBlank()) {
                        return;
                    }
                    // bot 自己的消息不会再过风控（全局阻断），发送前自查一道
                    String hitWord = sensitiveWordFilter.findHit(reply);
                    if (hitWord != null) {
                        log.warn("接茬回复命中敏感词，静默丢弃：group={}, word={}", groupId, hitWord);
                        return;
                    }
                    bot.sendGroupMsg(groupId, reply, false);
                    log.info("话题接茬已发送：group={}, keyword={}", groupId, text);
                } catch (BusinessTimeoutException e) {
                    log.warn("话题接茬超时(10s)，本次静默：group={}", groupId);
                } catch (Exception e) {
                    // 本地小模型经常抽风，接不上话就闭嘴，别往群里发失败提示
                    log.warn("话题接茬生成失败，本次静默：group={}, {}", groupId, e.getMessage());
                }
            });
        } catch (BusinessException e) {
            // 池满即放弃：主动接话没有回复义务，不做降级提示
            log.warn("AI 线程池已满，跳过本次接茬：group={}", groupId);
        }
    }

    private String buildPrompt(String text, List<String> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("群聊最近记录：\n");
        for (String line : context) {
            // 日志格式 "qq|内容"，qq 号对模型没意义，只喂内容
            int idx = line.indexOf('|');
            sb.append(idx >= 0 ? line.substring(idx + 1) : line).append('\n');
        }
        sb.append("（最后一句是刚说的。）围绕「").append(text)
                .append("」自然地接一句，20 字以内，不要@任何人，不要复读别人说的话。");
        return sb.toString();
    }
}
