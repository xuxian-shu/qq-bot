package com.qqbot.service.impl;

import com.qqbot.service.PointsService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 积分抽奖：给积分经济装上"下水道"。
 * <p>
 * 经济账：单次成本 {@code lottery.cost}（默认 20 分），奖池期望 =
 * 0×50% + 10×25% + 30×15% + 50×8% + 100×2% = 13 分，
 * 平均每抽净回收 7 分——排行榜卷王手里的存量积分有了消耗出口，
 * 通胀速度被按住（此前积分只进不出，冷场唤醒文案还许诺了"攒着抽奖"）。
 * <p>
 * 防白嫖三件套：
 * ① 扣费用条件 UPDATE（WHERE points >= cost），余额不足或并发抢扣都会失败，
 *    不存在"扣成负数再来个大奖"的组合；
 * ② 先扣费后开奖，任何一步崩了用户至多损失一次成本，不会白拿奖；
 * ③ 收支都走 {@link PointsService}，流水表里"积分抽奖"和"抽奖奖励"两行可完整对账。
 */
@Service
public class LotteryService {

    /** 奖池：(积分, 权重)，权重总和 100 方便口算期望 */
    private record Prize(int points, int weight) {
    }

    private static final List<Prize> PRIZES = List.of(
            new Prize(0, 50),    // 谢谢参与
            new Prize(10, 25),   // 回本一半
            new Prize(30, 15),   // 小赚
            new Prize(50, 8),    // 不错
            new Prize(100, 2)    // 大奖
    );
    private static final int TOTAL_WEIGHT = 100;

    @Value("${lottery.cost:20}")
    private int cost;

    @Resource
    private PointsService pointsService;

    /**
     * 抽一次。返回直接可发的回复文本（含余额），任何失败都是话术、不抛异常。
     */
    public String draw(long qq) {
        if (cost <= 0) {
            return "抽奖功能暂未开放喵～";
        }

        long balance = pointsService.getPoints(qq);
        if (balance < cost) {
            return "积分不够喵～抽奖一次要 " + cost + " 分，你现在有 " + balance
                    + " 分，先去签到/抢答攒一攒吧！";
        }

        // 条件扣费：并发双抽时只有一个能成功，另一个在这里被拦下
        if (!pointsService.tryDeduct(qq, cost, "积分抽奖")) {
            return "就差这一口气！积分刚好被同时花掉了喵，当前余额 "
                    + pointsService.getPoints(qq) + " 分，再攒攒～";
        }

        int prize = roll();
        if (prize > 0) {
            pointsService.addPoints(qq, prize, "抽奖奖励");
        }
        long after = pointsService.getPoints(qq);

        if (prize == 0) {
            return "🎰 消耗 " + cost + " 积分……谢谢参与喵，下次一定！当前余额 " + after + " 分";
        }
        return "🎰 消耗 " + cost + " 积分，中奖 " + prize + " 分！当前余额 " + after + " 分喵～";
    }

    /** 按权重掷骰子 */
    private int roll() {
        int r = ThreadLocalRandom.current().nextInt(TOTAL_WEIGHT);
        for (Prize p : PRIZES) {
            if (r < p.weight()) {
                return p.points();
            }
            r -= p.weight();
        }
        return 0; // 理论到不了，权重和恒等于 TOTAL_WEIGHT
    }
}
