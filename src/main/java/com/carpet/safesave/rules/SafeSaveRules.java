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
}
