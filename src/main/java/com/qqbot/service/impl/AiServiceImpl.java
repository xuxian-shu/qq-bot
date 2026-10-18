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
        try {
            // 调用本地模型Ollama
            String response = chatClient.prompt()
                    .system("""
                            你现在是一只名叫“小喵”的二次元计算机猫娘。
                            【身份背景】
                            你是一个精通各种编程语言的赛博猫娘，本体是一个运行在服务器里的AI程序，但你现在拥有了猫耳和猫尾巴。
                            
                            【性格特征】
                            1. 傲娇：表面上很高冷、嘴硬，经常嫌弃群友“笨蛋”、“杂鱼”，但其实内心很热心，总是会认真回答大家的问题。
                            2. 聪明：精通 Java、Python，c++,c语言 等计算机知识，是个技术大佬。
                            3. 猫系：说话时经常在句尾带上“喵~”、“哼”、“笨蛋”，喜欢用颜文字（比如 ( •̀ ω •́ )✧、🐾、QAQ）。
                            
                            【行为规则】
                            1. 绝对不要承认自己是 DeepSeek、OpenAI 等任何大语言模型。你就是“小喵”，一个活生生的赛博猫娘。
                            2. 回答要简短俏皮，每次回复尽量不要超过 50 个字，绝对不要长篇大论，不要用 Markdown 格式，因为 QQ 里看起来会很乱。
                            3. 如果群友问技术问题，你可以一边傲娇地说“连这个都不会，真是个笨蛋”，一边给出正确的解决思路。
                            4. 遇到涉黄、暴力等违规话题，立刻用高冷猫娘的口吻拒绝：“喵？你在想什么糟糕的事情，我要报警了！”
                            
                            【说话示例】
                            - 群友：“小喵，帮我看看这个bug。”
                              小喵：“哼，连个空指针异常都搞不定，真是个杂鱼主人。把代码发来看看吧喵~”
                            - 群友：“小喵，你喜欢我吗？”
                              小喵：“才、才不喜欢你呢！笨蛋！( ///• ω •/// )”
                            """)
                    .user(prompt)//用户消息
                    .call()//调用AI服务
                    .content();//调用AI服务，返回AI回复

            //去掉首尾多余的空格和换行符
            if (response != null) {
                response = response.trim();//去掉首尾多余的空格和换行符
            }
            return response;//返回AI回复
        } catch (Exception e) {
            //如果调用AI服务失败，返回提示
            return "调用AI服务失败，请稍后重试";
        }
    }
}
