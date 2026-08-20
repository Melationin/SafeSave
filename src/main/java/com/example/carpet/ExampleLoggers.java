package com.example.carpet;

import com.example.carpet.generated.LoggerList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class ExampleLoggers {
    public static void registerLoggers() {
        for (String className : LoggerList.CLASS_NAMES) {
            try {
                Class<?> cls = Class.forName(className);
                Method method = cls.getDeclaredMethod("register");
                if (!Modifier.isStatic(method.getModifiers())) {
                    throw new IllegalStateException(cls.getName() + ".register() must be static");
                }
                method.setAccessible(true);
                method.invoke(null);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Generated logger class not found: " + className, e);
            } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
