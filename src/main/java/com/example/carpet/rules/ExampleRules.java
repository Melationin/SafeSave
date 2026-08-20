package com.example.carpet.rules;

import carpet.api.settings.Rule;
import com.example.carpet.annotation.RuleRegister;

import static carpet.api.settings.RuleCategory.FEATURE;

@RuleRegister
public class ExampleRules {
    public static final String EXAMPLE = "example";

    @Rule(categories = {EXAMPLE, FEATURE})
    public static boolean exampleFeature = false;
}
