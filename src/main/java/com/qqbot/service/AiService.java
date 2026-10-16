package com.qqbot.service;

public interface AiService {

    /**
     * 调用AI服务，传入用户消息和QQ号，返回AI回复
     * @param msg 用户消息
     * @param qq 用户QQ号
     * @return AI回复
     */
    String chat(String msg, String qq);
}
