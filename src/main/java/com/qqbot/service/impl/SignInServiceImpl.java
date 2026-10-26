package com.qqbot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qqbot.entity.SignInRecord;
import com.qqbot.entity.User;
import com.qqbot.mapper.SignInrecordMapper;
import com.qqbot.mapper.UserMapper;
import com.qqbot.service.BotImageService;
import com.qqbot.service.PointsService;
import com.qqbot.service.SignInService;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class SignInServiceImpl implements SignInService {

    // 签到奖励规则：基础 10 分，连续签到每多 1 天 +2（封顶 +10），10% 概率暴击翻倍
    private static final int BASE_POINTS = 10;
    private static final int STREAK_BONUS_CAP = 10;
    private static final int CRIT_RATE = 10;

    @Resource
    private UserMapper userMapper;

    @Resource
    private SignInrecordMapper signInrecordMapper;

    @Resource
    private BotImageService botImageService;

    @Resource
    private PointsService pointsService;

    @Override
    @Transactional
    public String signIn(long qq, String nickname, long groupId) {
        LocalDate today = LocalDate.now();//当前日期

        // 昵称可能拿不到（比如事件里没有发送者信息），为空就用默认称呼，
        // 避免卡片上显示 “昵称: null”
        String displayName = (nickname == null || nickname.isBlank()) ? "群友" : nickname.trim();

        // 1. 查今天是否已经签到过
        LambdaQueryWrapper<SignInRecord> query = new LambdaQueryWrapper<>();//去数据库查询是否有签到记录
        query.eq(SignInRecord::getQq, qq)
                .eq(SignInRecord::getSignDate, today);
        Long count = signInrecordMapper.selectCount(query);//查询是否有签到记录
        if (count > 0) {
            return "你今天已经签到过啦，明天再来吧！";
        }

        // 2. 查用户是否存在，不存在就创建
        LambdaQueryWrapper<User> userQuery = new LambdaQueryWrapper<>();
        userQuery.eq(User::getQq, qq);
        User user = userMapper.selectOne(userQuery);
        if (user == null) {
            user = new User();
            user.setQq(qq);
            user.setPoints(0);
            user.setNickname(displayName);
            user.setCreateTime(LocalDateTime.now());
            userMapper.insert(user);
        }

        // 3. 计算连击：昨天签过 → 上次连击天数 +1；断签或首次签到就重置为 1
        SignInRecord last = signInrecordMapper.selectOne(new LambdaQueryWrapper<SignInRecord>()
                .eq(SignInRecord::getQq, qq)
                .lt(SignInRecord::getSignDate, today)
                .orderByDesc(SignInRecord::getSignDate)
                .last("LIMIT 1"));
        int prevDays = 0;
        if (last != null && today.minusDays(1).equals(last.getSignDate())) {
            prevDays = last.getContinuousDays() == null ? 0 : last.getContinuousDays();
        }
        int continuousDays = prevDays + 1;

        // 4. 本次获得 = 基础分 + 连击加成（每连续 1 天 +2，封顶 +10），10% 概率暴击翻倍
        int gained = BASE_POINTS + Math.min((continuousDays - 1) * 2, STREAK_BONUS_CAP);
        boolean crit = ThreadLocalRandom.current().nextInt(100) < CRIT_RATE;
        if (crit) {
            gained *= 2;
        }

        // 5. 插入签到记录（群号一并存下，积分排行按群统计要用）
        SignInRecord record = new SignInRecord();
        record.setQq(qq);
        record.setGroupId(groupId);
        record.setSignDate(today);
        record.setContinuousDays(continuousDays);
        record.setCreateTime(LocalDateTime.now());
        try {
            signInrecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // (qq, sign_date) 唯一索引兜底：selectCount 和 insert 之间如果
            // 有并发连点挤进来，索引会让第二条插入失败，这里友好提示而不是报错
            return "你今天已经签到过啦，明天再来吧！";
        }

        // 6. 原子加积分，拿回变动后的最新余额给卡片显示
        long newPoints = pointsService.addPoints(qq, gained);

        // 7. 把昵称存进用户表（老用户每次签到刷新），积分排行榜显示名字要用
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .set(User::getNickname, displayName)
                .eq(User::getQq, qq));

        // 8. 生成签到卡片，把连击和暴击信息写在卡片第三行
        String extraLine = "连击 " + continuousDays + " 天，本次 +" + gained + (crit ? " 暴击x2！" : "");
        return botImageService.generateSignCardCqCode(qq, displayName, (int) newPoints, extraLine);
    }
}
