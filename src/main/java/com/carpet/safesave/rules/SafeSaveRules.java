package com.carpet.safesave.rules;

import carpet.api.settings.Rule;

import static carpet.api.settings.RuleCategory.FEATURE;

public class SafeSaveRules {


    @Rule(categories = { FEATURE})
    public static boolean safeSave = false;


    @Rule(categories = { FEATURE})
    public static boolean safeSaveRegions = false;

    @Rule(categories = { FEATURE}, options = {"no_freeze", "manual", "region"})
    public static String safeSaveUnfreeze = "manual";

    @Rule(categories = { FEATURE})
    public static int safeSaveRegionTimeout = 600;
}
