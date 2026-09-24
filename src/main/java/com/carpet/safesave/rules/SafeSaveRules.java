package com.carpet.safesave.rules;

import carpet.api.settings.Rule;

import static carpet.api.settings.RuleCategory.FEATURE;

public class SafeSaveRules {

    public final static String SAFESAVE="SafeSave";

    @Rule(categories = { FEATURE,SAFESAVE })
    public static boolean safeSave = false;

    @Rule(categories = { FEATURE,SAFESAVE },options = {"1200"},strict = false)
    public static int safeSaveTicketDuration = 60 * 20;

    @Rule(categories = { FEATURE,SAFESAVE },options = {"0","2400"},strict = false)
    public static int safeSaveForceUnfreezeTimeout = 0;

    @Rule(categories = { FEATURE,SAFESAVE },options = {"true","false"})
    public static boolean safeSaveTicketTimerFromFirstPlayer = true;
}
