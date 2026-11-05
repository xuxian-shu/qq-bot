package com.qqbot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qqbot.entity.QuizQuestion;
import com.qqbot.mapper.QuizQuestionMapper;
import com.qqbot.service.PointsService;
import com.qqbot.service.QuizService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class QuizServiceImpl implements QuizService {

    private static final Logger log = LoggerFactory.getLogger(QuizServiceImpl.class);

    // 首位答对的奖励积分
    private static final int REWARD_POINTS = 20;
    // 每人每日错误尝试计数（固定窗口：首次设 TTL，之后不续期）
    private static final String TRY_KEY_PREFIX = "quiz:try:";

    @Resource
    private QuizQuestionMapper quizQuestionMapper;

    @Resource
    private PointsService pointsService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 每人每天最多错误尝试次数：答题零成本 + 错误提示全群可见 = 可以无限暴力猜，
    // 单词/代码题答案空间有限，早晚会猜中。限次把"猜"的成本抬到和运气同量级
    @Value("${quiz.max-attempts:3}")
    private int maxAttempts;

    // 内存里缓存今日题目，避免每次答题都查库。
    // volatile：定时任务线程写、消息线程读
    private volatile QuizQuestion todayQuestion;

    @Override
    public String pushDailyQuestion(long targetGroupId) {
        // 新的一天，清掉昨天的内存状态
        todayQuestion = null;

        // 随机取一条没用过的题
        QuizQuestion q = quizQuestionMapper.selectOne(new LambdaQueryWrapper<QuizQuestion>()
                .isNull(QuizQuestion::getUsedDate)
                .last("ORDER BY RAND() LIMIT 1"));

        if (q == null) {
            return "📢 题库告急喵！题目都出完了，请联系管理员往 quiz_question 表里补充新题";
        }

        // 条件抢占这道题：WHERE used_date IS NULL。
        // 之前是先查后改（updateById），两个调度/两次触发会抢中同一题各发一遍；
        // 现在并发下只有一个能占成功，输家看到 0 行影响直接放弃本次推送
        LocalDate today = LocalDate.now();
        int claimed = quizQuestionMapper.update(null, new LambdaUpdateWrapper<QuizQuestion>()
                .set(QuizQuestion::getUsedDate, today)
                .set(QuizQuestion::getGroupId, targetGroupId)
                .eq(QuizQuestion::getId, q.getId())
                .isNull(QuizQuestion::getUsedDate));
        if (claimed == 0) {
            log.warn("每日一题抢占失败（可能重复调度）：questionId={}", q.getId());
            return "今天的题目状态有点冲突喵，本次推送跳过（题库没被消耗，稍后可重试）";
        }

        q.setUsedDate(today);
        q.setGroupId(targetGroupId);
        todayQuestion = q;

        return quizText(q);
    }

    @Override
    public String submitAnswer(long qq, String answer, long groupId) {
        QuizQuestion q = currentQuestion();
        if (q == null) {
            return "今天还没有出题喵，等早上 8 点的「今日一题」吧！";
        }

        // 绑群校验：抢答只允许发生在出题的那个群（历史数据 group_id 为 NULL 不限制）。
        // 之前任何群、任何加了机器人的好友群都能答全局的题白嫖 +20
        if (q.getGroupId() != null && q.getGroupId() != groupId) {
            return "今天的题是在别的群出的喵，请去出题的那个群抢答～";
        }

        // 已经有人抢答成功，题目封盘
        if (q.getWinnerQq() != null) {
            return "这道题已经被抢答成功啦，答案是「" + q.getAnswer() + "」，明天再来喵！";
        }

        // 每日尝试上限（只计错误尝试；答对或已被抢答不消耗）
        int used = usedAttempts(qq);
        if (used >= maxAttempts) {
            return "你今天 " + maxAttempts + " 次答题机会已经用完啦，明天再来喵～";
        }

        // 归一化后比对（忽略大小写、首尾空格、全角空格）
        if (!normalize(answer).equals(normalize(q.getAnswer()))) {
            recordAttempt(qq);
            int left = Math.max(maxAttempts - used - 1, 0);
            return "不对喵，再想想～（今天还剩 " + left + " 次机会）";
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

        // 加积分走原子加分并记流水
        pointsService.addPoints(qq, REWARD_POINTS, "每日一题抢答");
        q.setWinnerQq(qq);

        return "🎉 恭喜 [CQ:at,qq=" + qq + "] 抢答成功，+20 积分！\n答案就是：「" + q.getAnswer() + "」";
    }

    @Override
    public String todayQuestionText(long groupId) {
        QuizQuestion q = currentQuestion();
        if (q == null) {
            return "今天还没有出题喵，早上 8 点会自动推送！";
        }
        // 群聊查询且题不在本群：不泄露题目内容，防止跨群抄答案
        if (groupId > 0 && q.getGroupId() != null && q.getGroupId() != groupId) {
            return "今天的题在别的群喵，本群今天没有抢答活动～";
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
     * （今天出过哪道题、在哪个群、有没有被抢答，表里都有）
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
                + "发「@我 答题 你的答案」抢答（仅限本群）\n"
                + "第一位答对的群友 +" + REWARD_POINTS + " 积分，每人每天最多 "
                + maxAttempts + " 次机会！";
    }

    /**
     * 答案归一化：去首尾空格、全角空格转半角、转小写
     */
    private String normalize(String s) {
        return s == null ? "" : s.trim().replace('　', ' ').toLowerCase();
    }

    /** 当日已用错误次数。Redis 故障 fail-open：不限次，优先保证答题可用 */
    private int usedAttempts(long qq) {
        try {
            String v = stringRedisTemplate.opsForValue().get(tryKey(qq));
            return v == null ? 0 : Integer.parseInt(v);
        } catch (Exception e) {
            log.warn("答题次数计数读取失败，本次放行：qq={}", qq, e);
            return 0;
        }
    }

    /** 记一次错误尝试；固定窗口，第一次设 TTL，之后不续期 */
    private void recordAttempt(long qq) {
        try {
            String key = tryKey(qq);
            Long c = stringRedisTemplate.opsForValue().increment(key);
            if (c != null && c == 1L) {
                stringRedisTemplate.expire(key, Duration.ofHours(25));
            }
        } catch (Exception e) {
            log.warn("答题次数计数写入失败：qq={}", qq, e);
        }
    }

    /** key 带日期 = 天然的每日重置固定窗口 */
    private String tryKey(long qq) {
        return TRY_KEY_PREFIX + qq + ":" + LocalDate.now();
    }
}
