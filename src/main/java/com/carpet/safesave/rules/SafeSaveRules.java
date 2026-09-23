package com.carpet.safesave.rules;

import carpet.api.settings.Rule;

import static carpet.api.settings.RuleCategory.FEATURE;

public class SafeSaveRules {

    public final static String SAFESAVE="SafeSave";

    @Rule(categories = { FEATURE,SAFESAVE })
    public static boolean safeSave = false;


    @Rule(categories = { FEATURE,SAFESAVE })
    public static int safeSaveTicketDuration = 30 * 20;

    // Server ticks from the first real player join to forced unfreeze.
    @Rule(categories = { FEATURE,SAFESAVE })
    public static int safeSaveForceUnfreezeTimeout = 300 * 20;

    // Otherwise the ticket duration begins when the server unfreezes.
    @Rule(categories = { FEATURE,SAFESAVE })
    public static boolean safeSaveTicketTimerFromFirstPlayer = true;
}
