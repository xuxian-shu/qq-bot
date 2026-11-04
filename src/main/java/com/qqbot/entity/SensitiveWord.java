package com.qqbot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sensitive_word")
public class SensitiveWord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 敏感词，启用后在内存中匹配 */
    private String word;

    /** 1 启用，0 停用 */
    private Integer enabled;

    private LocalDateTime createTime;
}