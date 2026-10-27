package com.qqbot.service;

import java.time.Duration;

public interface ReminderService {

    /**
     * 注册一条定时提醒，到点后由调度线程主动发消息
     *
     * @param selfId  机器人自己的 QQ 号（到点后要用它找回 Bot 实例）
     * @param qq      要提醒的人
     * @param groupId 发生在哪个群；私聊传 0
     * @param content 提醒内容
     * @param delay   多久后提醒
     * @return true 注册成功；false 该用户挂着的提醒已满
     */
    boolean addReminder(long selfId, long qq, long groupId, String content, Duration delay);
}
