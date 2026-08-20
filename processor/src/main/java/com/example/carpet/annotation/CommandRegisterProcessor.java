package com.example.carpet.annotation;

import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;

@SupportedAnnotationTypes("com.example.carpet.annotation.CommandRegister")
public class CommandRegisterProcessor extends CarpetProcessor
{

    @Override
    public Class<? extends Annotation> annotation() {
        return CommandRegister.class;
    }

    @Override
    public String className() {
        return "CommandList";
    }

    @Override
    protected boolean validateStaticMethod(TypeElement type) {
        boolean found = false;

        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() == ElementKind.METHOD) {
                ExecutableElement m = (ExecutableElement) e;
                if (m.getSimpleName().contentEquals("register")
                        && m.getParameters().size() == 1
                        && m.getModifiers().contains(Modifier.STATIC)
                        && m.getModifiers().contains(Modifier.PUBLIC)) {
                    found = true;
                    break;
                }
            }
        }

        if (!found) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Classes annotated with @CommandRegister must declare: "
                            + "public static void register(CommandDispatcher<CommandSourceStack>)",
                    type
            );
        }
        return found;
    }
}
