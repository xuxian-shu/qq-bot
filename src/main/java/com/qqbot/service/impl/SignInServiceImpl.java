package com.qqbot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqbot.entity.SignInRecord;
import com.qqbot.entity.User;
import com.qqbot.mapper.SignInrecordMapper;
import com.qqbot.mapper.UserMapper;
import com.qqbot.service.BotImageService;
import com.qqbot.service.SignInService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class SignInServiceImpl implements SignInService {

    @Resource
    private UserMapper userMapper;

    @Resource
    private SignInrecordMapper signInrecordMapper;

    @Resource
    private BotImageService botImageService;

    @Override
    @Transactional
    public String signIn(long qq, String nickname) {
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
            user.setCreateTime(LocalDateTime.now());
            userMapper.insert(user);
        }

        // 3. 插入签到记录
        SignInRecord record = new SignInRecord();
        record.setQq(qq);
        record.setSignDate(today);
        record.setContinuousDays(1);
        record.setCreateTime(LocalDateTime.now());
        signInrecordMapper.insert(record);

        // 4. 加 10 积分
        user.setPoints(user.getPoints() + 10);
        userMapper.updateById(user);

        // 5. 返回结果
        //把数据交给图片服务，拿回 CQ 码（使用调用方传进来的真实昵称）
        return botImageService.generateSignCardCqCode(qq, displayName, user.getPoints());
    }
}