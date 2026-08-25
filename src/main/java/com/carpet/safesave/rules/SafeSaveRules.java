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
     * ProtectedRegion 功能总开关：开启后，定义的 region 才会按“全部区块可 tick 才一起 tick，
     * 缺一个区块就整 region 冻结”执行。
     */
    @Rule(categories = { FEATURE})
    public static boolean safeSaveRegions = false;

    /**
     * 启动冻结策略。
     *
     * <ul>
     *   <li>{@code no_freeze}：不冻结，SafeSave 数据在第一个正常 tick 用快照自带时间戳顺延恢复；</li>
     *   <li>{@code manual}：冻结一次，玩家确认后 {@code /tick unfreeze} 手动解冻（原行为）；</li>
     *   <li>{@code region}：根据 ProtectedRegion 解冻（预留，暂未实现）。</li>
     * </ul>
     */
    @Rule(categories = { FEATURE}, options = {"no_freeze", "manual", "region"})
    public static String safeSaveUnfreeze = "manual";
}
