package com.qqbot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 群打卡记录：解决"签到全局一天一次、排行却按群统计"的口径裂缝。
 * <p>
 * 背景：sign_in_record 每人每天只会有一条（发积分的那条，记的是当天第一个
 * 签到的群）。用户先在 A 群签到、再去 B 群 @我 签到时拿不到积分，
 * 于是永远进不了 B 群排行。
 * <p>
 * 本表记录"这个人在这个群今天露过签到脸"——不管积分发没发。
 * B 群排行 = 本表在 B 群出现过的 qq 集合。私聊签到（群号 0）不记录。
 */
@Data
@TableName("sign_in_group")
public class SignInGroup {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long qq;
    private Long groupId;
    private LocalDate signDate;
    private LocalDateTime createTime;
}
