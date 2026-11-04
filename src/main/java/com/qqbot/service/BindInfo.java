package com.qqbot.service;

/**
 * 一条 B站绑定关系。以 JSON 形式存 Redis（bili:user:{qq}）。
 * <p>
 * 安全提示：cookie 字段是用户的完整登录态（等价于密码），
 * 只允许存 Redis / 数据库，绝不能进 application.yml、日志或 Git。
 *
 * @param qq        绑定的 QQ 号
 * @param groupId   发起绑定的群号；0 表示私聊绑定
 * @param mid       B站 UID
 * @param uname     B站昵称（绑定那一刻的快照）
 * @param up        是否被判定为 UP 主（有播放/获赞数据）
 * @param cookie    登录态 Cookie 串
 * @param bindTime  绑定时间戳（毫秒）
 */
public record BindInfo(long qq, long groupId, long mid, String uname,
                       boolean up, String cookie, long bindTime) {
}