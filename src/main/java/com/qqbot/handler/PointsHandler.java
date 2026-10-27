package com.qqbot.handler;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotPlugin;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.message.PrivateMessageEvent;
import com.qqbot.entity.User;
import com.qqbot.service.PointsService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 积分查询与排行插件。
 * 私聊：积分 / 积分排行
 * 群聊：@我 积分 / @我 积分排行
 * <p>
 * 注意：新增指令后要同步到 AiHandler 的排除名单，否则 AI 会对这些词再回一条。
 */
@Component
public class PointsHandler extends BotPlugin {

    private static final int TOP_N = 10;//排行榜只展示前 10 名

    @Resource
    private PointsService pointsService;

    //私聊：查积分 / 总排行
    @Override
    public int onPrivateMessage(Bot bot, PrivateMessageEvent event) {
        String msg = event.getMessage();
        long qq = event.getUserId();

        if ("积分".equals(msg)) {
            bot.sendPrivateMsg(qq, pointsReply(qq), false);
            return MESSAGE_IGNORE;
        }
        if ("积分排行".equals(msg)) {
            bot.sendPrivateMsg(qq, formatRank("积分总排行", pointsService.rankGlobal(TOP_N)), false);
        }
        return MESSAGE_IGNORE;
    }

    //群聊：@我 积分 / @我 积分排行（按本群统计）
    @Override
    public int onGroupMessage(Bot bot, GroupMessageEvent event) {
        String msg = event.getMessage();
        long groupId = event.getGroupId();
        long qq = event.getUserId();

        // 动态获取机器人自己的 QQ 号，与其他 Handler 保持一致
        String atCode = "[CQ:at,qq=" + event.getSelfId() + "]";
        if (!msg.contains(atCode)) {
            return MESSAGE_IGNORE;
        }
        String cleanMsg = msg.replace(atCode, "").trim();

        if ("积分".equals(cleanMsg)) {
            bot.sendGroupMsg(groupId, pointsReply(qq), false);
            return MESSAGE_IGNORE;
        }
        if ("积分排行".equals(cleanMsg)) {
            List<User> top = pointsService.rankInGroup(groupId, TOP_N);
            if (top.isEmpty()) {
                bot.sendGroupMsg(groupId, "本群还没有人签到过喵，快 @我 签到抢个第一！", false);
            } else {
                bot.sendGroupMsg(groupId, formatRank("本群积分排行", top), false);
            }
        }
        return MESSAGE_IGNORE;
    }

    /**
     * 个人积分回复；积分为 0 时顺便引导去签到
     */
    private String pointsReply(long qq) {
        long points = pointsService.getPoints(qq);
        if (points <= 0) {
            return "你还没有积分喵，快去签到攒一点吧！";
        }
        return "你现在的积分：" + points + " 分，继续签到攒积分喵～";
    }

    /**
     * 排行榜文本，形如：
     * 本群积分排行 🏆
     * 1. 小明  250分
     * 2. …
     */
    private String formatRank(String title, List<User> users) {
        StringBuilder sb = new StringBuilder(title + " 🏆\n");
        for (int i = 0; i < users.size(); i++) {
            User u = users.get(i);
            // 老用户可能还没存过昵称（下次签到就会补上），先用占位名
            String name = (u.getNickname() == null || u.getNickname().isBlank()) ? "神秘人" : u.getNickname();
            int points = u.getPoints() == null ? 0 : u.getPoints();
            sb.append(i + 1).append(". ").append(name).append("  ").append(points).append("分");
            if (i < users.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
