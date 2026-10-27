package com.qqbot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qqbot.entity.SignInRecord;
import com.qqbot.entity.User;
import com.qqbot.mapper.SignInrecordMapper;
import com.qqbot.mapper.UserMapper;
import com.qqbot.service.PointsService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PointsServiceImpl implements PointsService {

    @Resource
    private UserMapper userMapper;

    @Resource
    private SignInrecordMapper signInrecordMapper;

    @Override
    public long getPoints(long qq) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getQq, qq));
        return (user == null || user.getPoints() == null) ? 0 : user.getPoints();
    }

    @Override
    public long addPoints(long qq, int delta) {
        // 数据库端原子自增，避免"先查出来、内存里加、再写回"被并发覆盖的问题。
        // delta 只来自内部代码（签到/游戏），不拼用户输入，没有注入风险
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .setSql("points = points + " + delta)
                .eq(User::getQq, qq));

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getQq, qq));
        return user == null ? 0 : user.getPoints();
    }

    @Override
    public List<User> rankInGroup(long groupId, int n) {
        // 先找出在本群签过到的 qq（去重），再按积分排序取前 n 名。
        // 数据量小，两步查询比写 join SQL 简单清晰
        List<SignInRecord> records = signInrecordMapper.selectList(new LambdaQueryWrapper<SignInRecord>()
                .select(SignInRecord::getQq)
                .eq(SignInRecord::getGroupId, groupId)
                .groupBy(SignInRecord::getQq));
        if (records.isEmpty()) {
            return List.of();
        }
        List<Long> qqs = records.stream().map(SignInRecord::getQq).toList();
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                .in(User::getQq, qqs)
                .orderByDesc(User::getPoints)
                .last("LIMIT " + n));
    }

    @Override
    public List<User> rankGlobal(int n) {
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                .orderByDesc(User::getPoints)
                .last("LIMIT " + n));
    }
}
