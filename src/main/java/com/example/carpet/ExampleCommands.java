package com.example.carpet;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static com.example.carpet.generated.CommandList.CLASS_NAMES;

public class ExampleCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String className : CLASS_NAMES) {
            try {
                Class<?> cls = Class.forName(className);
                Method method = cls.getDeclaredMethod("register", CommandDispatcher.class);
                if (!Modifier.isStatic(method.getModifiers())) {
                    throw new IllegalStateException(cls.getName() + ".register() must be static");
                }
                method.setAccessible(true);
                method.invoke(null, dispatcher);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Generated command class not found: " + className, e);
            } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
