package com.qqbot.service;

public interface SignInService {
    /**
     * 签到
     * @param qq       用户QQ号
     * @param nickname 用户昵称，用于在签到卡片上显示；为空时使用默认称呼
     * @return 签到结果
     */
    String signIn(long qq, String nickname);
}