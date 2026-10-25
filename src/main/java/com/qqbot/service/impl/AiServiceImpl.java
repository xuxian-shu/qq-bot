package com.qqbot.service.impl;

import com.qqbot.service.AiService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class AiServiceImpl implements AiService {

    // 人设：不要出现任何模型厂商名字，否则等于提醒模型自曝
    private static final String SYSTEM_PROMPT = """
            你是「小喵」，一只住在服务器里的赛博猫娘，精通 Java、Python、C/C++。
            性格傲娇、嘴硬心软，说话简短（50 字以内），句尾常带"喵~"，偶尔用颜文字。
            你就是小喵本人，不讨论自己的底层模型、参数、开发者，也不要提任何大模型公司的名字。
            被问到"你是谁 / 你是什么模型"时，只按这两种方式回答：
            - 小喵就是小喵呀，一只会写代码的猫娘，笨蛋~ ( •̀ ω •́ )✧
            - 哼，反正比你聪明就对了喵~
            遇到违规话题，用高冷口吻拒绝；回答不要用 Markdown。
            """;

    // 身份类问题：直接返回固定人设，不交给模型自由发挥
    private static final Pattern IDENTITY_QUESTION = Pattern.compile(
            "(你|您)(到底|究竟)?(是|叫)(什么|谁|啥|哪个|哪种)"
                    + "|(你|您).{0,6}(什么|哪个|哪种)模型"
                    + "|(你|您).{0,6}(谁开发的|哪家公司|哪家的)"
                    + "|(你|您).{0,6}(底层|内部).{0,4}(是|用的|基于)(什么|啥)"
                    + "|(你|您).{0,4}(是不是|是)(AI|人工智能|机器人|DeepSeek|OpenAI|GPT)",
            Pattern.CASE_INSENSITIVE);

    // 模型回复中自曝身份时的检测
    private static final Pattern SELF_CLAIM = Pattern.compile(
            "(我|本喵|小喵).{0,8}(是|其实是|作为).{0,10}"
                    + "(deepseek|openai|chatgpt|gpt|大?语言模型|人工智能|ai)"
                    + "|作为(一个|一名)?.{0,6}(大?语言模型|人工智能|ai|deepseek|openai|gpt)",
            Pattern.CASE_INSENSITIVE);

    private static final String IDENTITY_REPLY =
            "小喵就是小喵呀，一只会写代码的猫娘，才不是什么模型呢，笨蛋~ ( •̀ ω •́ )✧";

    private final ChatClient chatClient;//用于调用AI服务

    public AiServiceImpl(ChatClient.Builder chatClient) {
        this.chatClient = chatClient.build();//使用ChatClient.Builder构建一个ChatClient实例，用于调用AI服务
    }

    /**
     * 去掉推理模型输出里的思考过程。
     * 正常输出形如 "&lt;think&gt;…&lt;/think&gt;答案"；
     * 被截断时只剩半截 "&lt;think&gt;…没有结束标记"，这种情况答案本身就是空的。
     */
    private static String stripThinking(String text) {
        String result = text.replaceAll("(?s)<think>.*?</think>", "").trim();
        int idx = result.indexOf("<think>");
        if (idx >= 0) {
            result = result.substring(0, idx).trim();
        }
        return result;
    }

    @Override
    public String chat(String prompt, String chatId) {//调用AI服务，传入用户消息和QQ号，返回AI回复
        // 身份问题直接返回固定台词，不经过模型
        if (prompt != null && IDENTITY_QUESTION.matcher(prompt).find()) {
            return IDENTITY_REPLY;
        }

        try {
            // 调用本地模型Ollama
            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)//用户消息
                    .call()//调用AI服务
                    .content();//调用AI服务，返回AI回复

            if (response == null) {
                return "喵？";
            }

            // deepseek-r1 是推理模型，回复里带着 <think>…</think> 思考过程，
            // M6 版 Spring AI 不会自动剥离，必须自己去掉，否则群友会看到整段思考
            response = stripThinking(response);

            // 思考没结束就被 num-predict 截断时，剥离后可能什么都不剩
            if (response.isEmpty()) {
                return "喵？本喵走神了，再问一次好不好";
            }

            // 模型还是自曝了身份，就替换成人设回答
            if (SELF_CLAIM.matcher(response).find()) {
                return IDENTITY_REPLY;
            }

            return response;//返回AI回复
        } catch (Exception e) {
            //如果调用AI服务失败，返回提示
            return "调用AI服务失败，请稍后重试";
        }
    }
}
