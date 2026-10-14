package com.qqbot.service;

public interface SignInService {
    /**
     * 签到
     * @param qq 用户QQ号
     * @return 签到结果
     */
    String signIn(long qq);
}