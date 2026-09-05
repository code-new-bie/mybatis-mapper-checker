package com.mapperchecker.core;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * core 层文案访问。与 idea 层的 MapperCheckerBundle 读同一个 properties 文件，
 * 但这里只依赖 JDK，便于 core 单测直接断言中文文案。
 */
public final class Messages {

    private static final String BUNDLE = "messages.MapperCheckerBundle";

    private Messages() {
    }

    public static String get(String key, Object... args) {
        String pattern;
        try {
            pattern = ResourceBundle.getBundle(BUNDLE, Locale.ROOT, Messages.class.getClassLoader()).getString(key);
        } catch (MissingResourceException e) {
            return "!" + key + "!";
        }
        return args.length == 0 ? pattern.replace("''", "'") : new MessageFormat(pattern, Locale.ROOT).format(args);
    }
}
