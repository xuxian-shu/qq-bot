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
     * 指定群的积分排行：在本群签过到的用户里，按积分取前 n 名
     */
    List<User> rankInGroup(long groupId, int n);

    /**
     * 全部用户的积分排行，按积分取前 n 名
     */
    List<User> rankGlobal(int n);
}
