package com.qqbot.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qqbot.entity.PointsLog;
import com.qqbot.entity.SignInGroup;
import com.qqbot.entity.SignInRecord;
import com.qqbot.entity.User;
import com.qqbot.mapper.PointsLogMapper;
import com.qqbot.mapper.SignInGroupMapper;
import com.qqbot.mapper.SignInrecordMapper;
import com.qqbot.mapper.UserMapper;
import com.qqbot.service.PointsService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class PointsServiceImpl implements PointsService {

    private static final Logger log = LoggerFactory.getLogger(PointsServiceImpl.class);

    @Resource
    private UserMapper userMapper;

    @Resource
    private SignInrecordMapper signInrecordMapper;

    @Resource
    private SignInGroupMapper signInGroupMapper;

    @Resource
    private PointsLogMapper pointsLogMapper;

    @Override
    public long getPoints(long qq) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getQq, qq));
        return (user == null || user.getPoints() == null) ? 0 : user.getPoints();
    }

    @Override
    public long addPoints(long qq, int delta, String reason) {
        // 用户不存在时先零分建档再加，以前是裸 UPDATE：qq 不在 user 表时
        // 更新 0 行、静默丢分（典型触发：跨群答题中奖者从未在本机器人建过档）
        ensureUser(qq);

        // 数据库端原子自增，避免"先查出来、内存里加、再写回"被并发覆盖的问题。
        // delta 只来自内部代码（签到/答题/抽奖），不拼用户输入，没有注入风险
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .setSql("points = points + " + delta)
                .eq(User::getQq, qq));

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getQq, qq));
        long balance = user == null || user.getPoints() == null ? 0 : user.getPoints();
        writeLog(qq, delta, balance, reason);
        return balance;
    }

    @Override
    public boolean registerIfAbsent(long qq, String nickname, int initialPoints) {
        // LIMIT 1 兜底：万一 user 表没建 qq 唯一索引、历史数据有重复，也不会抛 TooManyResults
        User existing = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getQq, qq)
                .last("LIMIT 1"));
        if (existing != null) {
            return false;
        }

        User user = new User();
        user.setQq(qq);
        user.setNickname(nickname == null || nickname.isBlank() ? "群友" : nickname.trim());
        user.setPoints(Math.max(initialPoints, 0));
        user.setCreateTime(LocalDateTime.now());
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发下别的线程已经建档，本次不重复送分
            return false;
        }
        if (initialPoints > 0) {
            writeLog(qq, initialPoints, initialPoints, "首次建档赠送");
        }
        return true;
    }

    @Override
    public long deductPoints(long qq, int delta, String reason) {
        if (delta <= 0) {
            return getPoints(qq);
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getQq, qq));
        if (user == null) {
            // 没建档的人没有可扣的分，也不为其建档（避免违规者借扣分"洗白"进总排行）
            return 0;
        }
        int before = user.getPoints() == null ? 0 : user.getPoints();

        // GREATEST 保证扣到 0 就停，避免用户积分为负
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .setSql("points = GREATEST(points - " + delta + ", 0)")
                .eq(User::getQq, qq));
        long balance = getPoints(qq);

        // 流水记实际扣掉的量（余额不足 5 只扣了 3，就记 -3，账要能对上）
        writeLog(qq, -(int) (before - balance), balance, reason);
        return balance;
    }

    @Override
    public boolean tryDeduct(long qq, int delta, String reason) {
        if (delta <= 0) {
            return true;
        }
        // 条件更新：WHERE qq = ? AND points >= delta。
        // 余额不足或并发被抢先，更新影响 0 行，一分都不会扣
        int rows = userMapper.update(null, new LambdaUpdateWrapper<User>()
                .setSql("points = points - " + delta)
                .eq(User::getQq, qq)
                .ge(User::getPoints, delta));
        if (rows == 0) {
            return false;
        }
        long balance = getPoints(qq);
        writeLog(qq, -delta, balance, reason);
        return true;
    }

    @Override
    public List<User> rankInGroup(long groupId, int n) {
        // 排行口径 = 在本群"露过签到脸"的人，两个来源取并集：
        // ① sign_in_record：历史上真正发过积分的签到（groupId 记的是当天第一个签到群）
        // ② sign_in_group：新加的群打卡表，补上"已在别的群签过、在本群只是打卡"的人——
        //    这类人以前永远进不了本群排行，就是这个口径裂缝的受害者
        Set<Long> qqs = new LinkedHashSet<>();

        signInrecordMapper.selectList(new LambdaQueryWrapper<SignInRecord>()
                        .select(SignInRecord::getQq)
                        .eq(SignInRecord::getGroupId, groupId)
                        .groupBy(SignInRecord::getQq))
                .forEach(r -> qqs.add(r.getQq()));

        signInGroupMapper.selectList(new LambdaQueryWrapper<SignInGroup>()
                        .select(SignInGroup::getQq)
                        .eq(SignInGroup::getGroupId, groupId)
                        .groupBy(SignInGroup::getQq))
                .forEach(r -> qqs.add(r.getQq()));

        if (qqs.isEmpty()) {
            return List.of();
        }
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                .in(User::getQq, new ArrayList<>(qqs))
                .orderByDesc(User::getPoints)
                .last("LIMIT " + n));
    }

    @Override
    public List<User> rankGlobal(int n) {
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                .orderByDesc(User::getPoints)
                .last("LIMIT " + n));
    }

    /** 用户不存在就零分建档；并发撞唯一索引就当已存在 */
    private void ensureUser(long qq) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getQq, qq));
        if (count != null && count > 0) {
            return;
        }
        try {
            User user = new User();
            user.setQq(qq);
            user.setNickname("群友");
            user.setPoints(0);
            user.setCreateTime(LocalDateTime.now());
            userMapper.insert(user);
        } catch (DuplicateKeyException ignored) {
            // 别的线程刚建好，正好
        }
    }

    /**
     * 写流水。账本是审计设施不是业务前置条件：
     * 记不上只告警，绝不因为流水失败把签到/抽奖整个拖崩。
     */
    private void writeLog(long qq, int delta, long balanceAfter, String reason) {
        try {
            PointsLog entry = new PointsLog();
            entry.setQq(qq);
            entry.setDelta(delta);
            entry.setBalanceAfter((int) balanceAfter);
            entry.setReason(reason == null || reason.isBlank() ? "未注明" : reason);
            entry.setCreateTime(LocalDateTime.now());
            pointsLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("积分流水写入失败：qq={}, delta={}, reason={}", qq, delta, reason, e);
        }
    }
}
