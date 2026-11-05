package com.qqbot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 积分流水：每一次加分/扣分都留一行底账。
 * <p>
 * 用途：对账（余额对不上流水就是 bug）、申诉（用户说"我没被扣过 5 分"能查）、
 * 反作弊（积分倒卖/脚本刷分的模式在流水里一眼可见，在余额里看不出来）。
 * 只增不改不删，业务代码永远不要 UPDATE 这张表。
 */
@Data
@TableName("points_log")
public class PointsLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long qq;
    private Integer delta;        // 变动值，正=收入，负=支出
    private Integer balanceAfter; // 变动后的余额快照
    private String reason;        // 变动原因：每日签到 / 每日一题抢答 / 违规扣分 / 积分抽奖 ...
    private LocalDateTime createTime;
}
