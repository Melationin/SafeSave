package com.example.carpet.annotation;

import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.lang.annotation.Annotation;

@SupportedAnnotationTypes("com.example.carpet.annotation.RuleRegister")
public class RuleRegisterProcessor extends CarpetProcessor
{

    @Override
    public Class<? extends Annotation> annotation() {
        return RuleRegister.class;
    }

    @Override
    public String className() {
        return "SettingList";
    }

    @Override
    protected boolean validateStaticMethod(TypeElement type) {
        return true;
    }
}
