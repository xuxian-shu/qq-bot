package com.qqbot.service;

public interface QuizService {

    /**
     * 每天 8 点由定时任务调用：随机取一条没用过的题标记为今日题目并绑定到
     * targetGroupId，返回推题文案；题库用完返回告急文案。
     * 标记用条件 UPDATE 抢占（WHERE used_date IS NULL），并发/重复调度只成功一次
     */
    String pushDailyQuestion(long targetGroupId);

    /**
     * 处理群友答题，返回要回复到群里的文本（判对/判错/已被抢答/不在本群/次数用完等）。
     * 抢答只允许发生在出题的群；答错计入每人每日尝试上限（quiz.max-attempts）
     */
    String submitAnswer(long qq, String answer, long groupId);

    /**
     * 查看今日题目（含是否已被抢答的状态）。groupId=0 表示私聊查询，不做绑群校验
     */
    String todayQuestionText(long groupId);

    /**
     * 推送失败时回滚：把今天的 used_date 清掉、清空内存状态，
     * 下次推送可以重新用到这道题（避免题目被白白发掉）
     */
    void resetToday();
}
