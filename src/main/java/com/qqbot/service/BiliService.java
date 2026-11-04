package com.qqbot.service;

import com.mikuac.shiro.core.Bot;

import java.util.List;

/**
 * B站助手的业务入口：扫码绑定、解绑、个人卡片、个性化推荐、UP 主日报。
 * <p>
 * 绑定流程：生成二维码 → 发消息（优先私聊）→ 异步轮询扫码结果 →
 * 用 nav 接口校验登录态 → 存 Redis。轮询在独立线程进行，不阻塞消息线程。
 */
public interface BiliService {

    /**
     * 发起扫码绑定。二维码优先私聊发送（防群里被截胡），
     * 私聊发不出去（非好友）再退回群聊并提示本人扫码。
     * 本方法立刻返回，轮询在异步线程里进行。
     */
    void startBinding(Bot bot, long qq, long groupId);

    /** 解绑：删除登录态与日报快照，返回 false 表示本来就没绑定 */
    boolean unbind(long qq);

    boolean isBound(long qq);

    /** "我的B站"卡片：等级/硬币/大会员/UP主数据；未绑定或登录态失效返回话术 */
    String buildProfile(long qq);

    /** "今晚看什么"：从首页推荐里随机挑 3 条 */
    String buildRecommendation(long qq);

    /** 所有绑定关系，供日报调度器遍历 */
    List<BindInfo> listBindings();

    /** 生成某个绑定用户的 UP 主数据日报（含与昨日快照的增量）；非 UP 或拉取失败返回 null */
    String buildDailyReport(BindInfo user);
}