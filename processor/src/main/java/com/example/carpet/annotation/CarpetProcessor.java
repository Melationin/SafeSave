package com.example.carpet.annotation;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;

public abstract class CarpetProcessor extends AbstractProcessor {

    private boolean generated = false;

    public abstract Class<? extends java.lang.annotation.Annotation> annotation();

    public abstract String className();

    @Override
    public boolean process(Set<? extends TypeElement> annotations,
                           RoundEnvironment roundEnv) {
        if (generated || roundEnv.processingOver()) {
            return false;
        }

        Set<? extends Element> elements =
                roundEnv.getElementsAnnotatedWith(annotation());

        if (elements.isEmpty()) return false;

        try {
            generateRegistry(elements);
            generated = true;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return true;
    }

    private void generateRegistry(Set<? extends Element> elements)
            throws IOException {
        String pkg = "com.example.carpet.generated";
        String cls = className();

        JavaFileObject file = processingEnv
                .getFiler()
                .createSourceFile(pkg + "." + cls);

        try (Writer w = file.openWriter()) {
            w.write("package " + pkg + ";\n\n");
            w.write("import java.util.List;\n\n");
            w.write("public final class " + cls + " {\n");
            w.write("  public static final List<String> CLASS_NAMES = List.of(\n");

            boolean first = true;
            for (Element e : elements) {
                TypeElement t = (TypeElement) e;
                if (validateStaticMethod(t)) {
                    if (!first) {
                        w.write(",\n");
                    }
                    w.write("    \"" + t.getQualifiedName() + "\"");
                    first = false;
                }
            }

            w.write("\n  );\n");
            w.write("}\n");
        }
    }

    protected abstract boolean validateStaticMethod(TypeElement type);
}
