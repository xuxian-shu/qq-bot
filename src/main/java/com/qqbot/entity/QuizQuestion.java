package com.qqbot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("quiz_question")
public class QuizQuestion {//每日一题的题目
    @TableId(type = IdType.AUTO)
    private Long id;
    private String type;//题型：WORD 单词 / CODE 代码输出
    private String question;//题面
    private String answer;//标准答案
    private LocalDate usedDate;//使用日期（NULL = 还没出过）
    private Long winnerQq;//首位答对的人（NULL = 还没被抢答）
    private LocalDateTime winnerTime;//抢答时间
}
