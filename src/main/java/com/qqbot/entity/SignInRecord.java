package com.qqbot.entity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;


@Data
@TableName("sign_in_record")
public class SignInRecord {//签到记录
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long qq;
    private LocalDate signDate;
    private Integer continuousDays;
    private LocalDateTime createTime;
}
