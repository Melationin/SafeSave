package com.carpet.safesave.rules;

import carpet.api.settings.Rule;

import static carpet.api.settings.RuleCategory.FEATURE;
public class SafeSaveRules {

    /**
     * Persist scheduled ticks losslessly across a server restart.
     *
     * <p>Writes {@code <world>/safesave.dat} holding every scheduled tick with its
     * <em>absolute</em> {@code triggerTick} and original global {@code subTickOrder}, plus
     * {@code Level.subTickCount}. On startup the server is frozen before its first tick and the saved
     * ticks replace whatever vanilla re-anchored, so cross-chunk tick phase and ordering survive.
     */
    @Rule(categories = { FEATURE})
    public static boolean safeSave = false;
}
