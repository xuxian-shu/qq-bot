package com.qqbot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qqbot.entity.QuizQuestion;
import com.qqbot.mapper.QuizQuestionMapper;
import com.qqbot.service.PointsService;
import com.qqbot.service.QuizService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class QuizServiceImpl implements QuizService {

    // 首位答对的奖励积分
    private static final int REWARD_POINTS = 20;

    @Resource
    private QuizQuestionMapper quizQuestionMapper;

    @Resource
    private PointsService pointsService;

    // 内存里缓存今日题目，避免每次答题都查库。
    // volatile：定时任务线程写、消息线程读
    private volatile QuizQuestion todayQuestion;

    @Override
    public String pushDailyQuestion() {
        // 新的一天，清掉昨天的内存状态
        todayQuestion = null;

        // 随机取一条没用过的题
        QuizQuestion q = quizQuestionMapper.selectOne(new LambdaQueryWrapper<QuizQuestion>()
                .isNull(QuizQuestion::getUsedDate)
                .last("ORDER BY RAND() LIMIT 1"));

        if (q == null) {
            return "📢 题库告急喵！题目都出完了，请联系管理员往 quiz_question 表里补充新题";
        }

        // 占住这道题（标记今天用过），防止同一天重复推送
        q.setUsedDate(LocalDate.now());
        quizQuestionMapper.updateById(q);
        todayQuestion = q;

        return quizText(q);
    }

    @Override
    public String submitAnswer(long qq, String answer) {
        QuizQuestion q = currentQuestion();
        if (q == null) {
            return "今天还没有出题喵，等早上 8 点的「今日一题」吧！";
        }

        // 已经有人抢答成功，题目封盘
        if (q.getWinnerQq() != null) {
            return "这道题已经被抢答成功啦，答案是「" + q.getAnswer() + "」，明天再来喵！";
        }

        // 归一化后比对（忽略大小写、首尾空格、全角空格）
        if (!normalize(answer).equals(normalize(q.getAnswer()))) {
            return "不对喵，再想想～";
        }

        // 答对了：用带条件的 UPDATE 抢占封题（WHERE winner_qq IS NULL）。
        // 并发抢答时数据库只会让一个人成功，天然防止两个"第一名"
        int updated = quizQuestionMapper.update(null, new LambdaUpdateWrapper<QuizQuestion>()
                .set(QuizQuestion::getWinnerQq, qq)
                .set(QuizQuestion::getWinnerTime, LocalDateTime.now())
                .eq(QuizQuestion::getId, q.getId())
                .isNull(QuizQuestion::getWinnerQq));
        if (updated == 0) {
            return "就差一步！这道题刚刚被别人抢走啦";
        }

        // 加积分走现成的原子加分
        pointsService.addPoints(qq, REWARD_POINTS);
        q.setWinnerQq(qq);

        return "🎉 恭喜 [CQ:at,qq=" + qq + "] 抢答成功，+20 积分！\n答案就是：「" + q.getAnswer() + "」";
    }

    @Override
    public String todayQuestionText() {
        QuizQuestion q = currentQuestion();
        if (q == null) {
            return "今天还没有出题喵，早上 8 点会自动推送！";
        }
        if (q.getWinnerQq() != null) {
            return "今天的题已经被抢答啦，答案是「" + q.getAnswer() + "」。\n明天 8 点再来喵～";
        }
        return quizText(q);
    }

    @Override
    public void resetToday() {
        todayQuestion = null;
        // 把今天的占用标记清掉，让这道题下次还能被抽到
        quizQuestionMapper.update(null, new LambdaUpdateWrapper<QuizQuestion>()
                .set(QuizQuestion::getUsedDate, null)
                .eq(QuizQuestion::getUsedDate, LocalDate.now())
                .isNull(QuizQuestion::getWinnerQq));
    }

    /**
     * 取今日题目；机器人中途重启过内存是空的，就从数据库恢复
     * （今天出过哪道题、有没有被抢答，表里都有）
     */
    private QuizQuestion currentQuestion() {
        QuizQuestion q = todayQuestion;
        if (q != null) {
            return q;
        }
        q = quizQuestionMapper.selectOne(new LambdaQueryWrapper<QuizQuestion>()
                .eq(QuizQuestion::getUsedDate, LocalDate.now())
                .last("LIMIT 1"));
        if (q != null) {
            todayQuestion = q;
        }
        return q;
    }

    /**
     * 推题文案
     */
    private String quizText(QuizQuestion q) {
        String typeName = "CODE".equals(q.getType()) ? "代码题" : "单词题";
        return "📢 今日一题来啦喵！\n\n"
                + "【" + typeName + "】" + q.getQuestion() + "\n\n"
                + "发「@我 答题 你的答案」抢答\n"
                + "第一位答对的群友 +" + REWARD_POINTS + " 积分！";
    }

    /**
     * 答案归一化：去首尾空格、全角空格转半角、转小写
     */
    private String normalize(String s) {
        return s == null ? "" : s.trim().replace('　', ' ').toLowerCase();
    }
}
