package com.carpet.safesave.rules;

import carpet.api.settings.Rule;

import static carpet.api.settings.RuleCategory.FEATURE;

public class SafeSaveRules {

    /**
     * 在服务端重启后无损持久化计划刻。
     *
     * <p>写入 {@code <world>/safesave.dat}，保存每个计划刻的<em>绝对</em> {@code triggerTick} 与
     * 原始全局 {@code subTickOrder}，以及 {@code Level.subTickCount}。启动时服务端会在第一个刻之前
     * 冻结，保存的刻会替换原版重新锚定的内容，因此跨区块的刻相位与顺序得以保留。
     */
    @Rule(categories = { FEATURE})
    public static boolean safeSave = false;

    /**
     * ProtectedRegion 启动恢复屏障总开关。保存时记录完整加载的 Region；region 解冻模式下，
     * 重启会全局冻结并等待这些 Region 再次完整加载。
     */
    @Rule(categories = { FEATURE})
    public static boolean safeSaveRegions = false;

    /**
     * 启动冻结策略。
     *
     * <ul>
     *   <li>{@code no_freeze}：不冻结，SafeSave 数据在第一个正常 tick 用快照自带时间戳顺延恢复；</li>
     *   <li>{@code manual}：冻结一次，玩家确认后 {@code /tick unfreeze} 手动解冻（原行为）；</li>
     *   <li>{@code region}：全局冻结，等待上次保存时完整加载的 ProtectedRegion 后自动解冻。</li>
     * </ul>
     */
    @Rule(categories = { FEATURE}, options = {"no_freeze", "manual", "region"})
    public static String safeSaveUnfreeze = "manual";

    /**
     * {@code safeSaveUnfreeze=region} 的最大等待时间，单位为服务器刻。
     * 全局冻结时 gameTime 不前进，因此必须用服务器刻计时；{@code 0} 表示立即超时放行。
     */
    @Rule(categories = { FEATURE})
    public static int safeSaveRegionTimeout = 600;
}
