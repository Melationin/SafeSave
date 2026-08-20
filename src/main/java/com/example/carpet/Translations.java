package com.example.carpet;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

public class Translations
{
    private static final Gson GSON = new Gson();

    public static Map<String, String> getTranslationFromResourcePath(String lang) {
        String path = "assets/safesave/lang/%s.json".formatted(lang);
        try (InputStream in = Translations.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return Collections.emptyMap();
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> map = GSON.fromJson(json, new TypeToken<Map<String, String>>() {}.getType());
            return map != null ? map : Collections.emptyMap();
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }
}
