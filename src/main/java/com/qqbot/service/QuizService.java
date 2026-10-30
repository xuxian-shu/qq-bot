package com.qqbot.service;

public interface QuizService {

    /**
     * 每天 8 点由定时任务调用：随机取一条没用过的题标记为今日题目，
     * 返回要发到群里的推题文案；题库用完返回告急文案
     */
    String pushDailyQuestion();

    /**
     * 处理群友答题，返回要回复到群里的文本（判对/判错/已被抢答等）
     */
    String submitAnswer(long qq, String answer);

    /**
     * 查看今日题目（含是否已被抢答的状态）
     */
    String todayQuestionText();

    /**
     * 推送失败时回滚：把今天的 used_date 清掉、清空内存状态，
     * 下次推送可以重新用到这道题（避免题目被白白发掉）
     */
    void resetToday();
}
