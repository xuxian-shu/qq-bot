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
     * 调用方需保证用户记录已存在；返回变动后的最新余额
     */
    long addPoints(long qq, int delta);

    /**
     * 用户不存在时建档并送初始积分。返回 true 表示本次确实新建了用户，
     * 调用方可以据此判断“是不是第一次进群”，避免退群再进重复领奖励。
     */
    boolean registerIfAbsent(long qq, String nickname, int initialPoints);

    /**
     * 扣积分，最低扣到 0，不会出现负分。
     */
    long deductPoints(long qq, int delta);

    /**
     * 指定群的积分排行：在本群签过到的用户里，按积分取前 n 名
     */
    List<User> rankInGroup(long groupId, int n);

    /**
     * 全部用户的积分排行，按积分取前 n 名
     */
    List<User> rankGlobal(int n);
}
