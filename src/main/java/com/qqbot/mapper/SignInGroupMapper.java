package com.qqbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qqbot.entity.SignInGroup;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

@Mapper
public interface SignInGroupMapper extends BaseMapper<SignInGroup> {

    /**
     * INSERT IGNORE 打卡：撞 uk_qq_group_date 唯一索引就静默跳过。
     * 同一人在同一群一天点 N 次签到，本表永远只有一条，不用先查再插（查插之间有并发缝）。
     */
    @Insert("INSERT IGNORE INTO sign_in_group (qq, group_id, sign_date) "
            + "VALUES (#{qq}, #{groupId}, #{signDate})")
    int insertIgnore(@Param("qq") long qq,
                     @Param("groupId") long groupId,
                     @Param("signDate") LocalDate signDate);
}
