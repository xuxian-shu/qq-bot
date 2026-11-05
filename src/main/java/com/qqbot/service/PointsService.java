package com.qqbot.service;

import com.qqbot.entity.User;

import java.util.List;

public interface PointsService {

    /**
     * 查询用户积分，用户不存在时返回 0
     */
    long getPoints(long qq);

    /**
     * 原子加积分（数据库端执行 points = points + delta，不怕并发）。
     * 用户不存在时会先建档再加，不会静默丢分；每次变动都写一行流水。
     * 返回变动后的最新余额
     */
    long addPoints(long qq, int delta, String reason);

    /**
     * 用户不存在时建档并送初始积分。返回 true 表示本次确实新建了用户，
     * 调用方可以据此判断“是不是第一次进群”，避免退群再进重复领奖励。
     * 赠送的初始积分同样写流水。
     */
    boolean registerIfAbsent(long qq, String nickname, int initialPoints);

    /**
     * 扣积分，最低扣到 0，不会出现负分。用户不存在直接返回 0（不建档）。
     */
    long deductPoints(long qq, int delta, String reason);

    /**
     * 条件扣费：仅当余额 ≥ delta 时才扣，原子完成。
     * 返回 false 表示余额不足（或并发下被别人抢先扣了），一分都没扣。
     * 抽奖这类"先扣钱再给货"的场景必须用它，不能用 deductPoints
     * （后者会扣到 0 就停，让白嫖者零成本开奖）。
     */
    boolean tryDeduct(long qq, int delta, String reason);

    /**
     * 指定群的积分排行：在本群签过到的用户里，按积分取前 n 名
     */
    List<User> rankInGroup(long groupId, int n);

    /**
     * 全部用户的积分排行，按积分取前 n 名
     */
    List<User> rankGlobal(int n);
}
