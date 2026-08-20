package com.example.carpet;

import com.example.carpet.generated.SettingList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ExampleSettings {
    public static List<Class<?>> ruleClasses = Collections.emptyList();

    public static void loadClasses() {
        List<Class<?>> classes = new ArrayList<>();
        for (String className : SettingList.CLASS_NAMES) {
            try {
                classes.add(Class.forName(className));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Generated rule class not found: " + className, e);
            }
        }
        ruleClasses = Collections.unmodifiableList(classes);
    }
}
