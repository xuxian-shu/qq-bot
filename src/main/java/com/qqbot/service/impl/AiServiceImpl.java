package com.qqbot.service.impl;

import com.qqbot.service.AiService;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class AiServiceImpl implements AiService {

    private final ChatClient chatClient;//用于调用AI服务

    public AiServiceImpl(ChatClient.Builder chatClient) {
        this.chatClient = chatClient.build();//使用ChatClient.Builder构建一个ChatClient实例，用于调用AI服务
    }

    @Override
    public String chat(String prompt, String chatId) {//调用AI服务，传入用户消息和QQ号，返回AI回复
        //调用本地模型Ollama
        String response = chatClient.prompt()
                .user(prompt)//设置用户消息
                .call()//调用AI服务，传入用户消息
                .content();//返回AI回复

        //去掉首尾多余的空格和换行符
        if (response != null) {
            response = response.trim();//去掉首尾多余的空格和换行符
        }
        return response;
    }
}
