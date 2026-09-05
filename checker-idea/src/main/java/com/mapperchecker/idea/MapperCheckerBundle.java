package com.mapperchecker.idea;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

import java.util.function.Supplier;

/**
 * 插件文案入口。所有面向开发者的文字都从这里取，默认中文。
 */
public final class MapperCheckerBundle {

    private static final String BUNDLE = "messages.MapperCheckerBundle";
    private static final DynamicBundle INSTANCE = new DynamicBundle(MapperCheckerBundle.class, BUNDLE);

    private MapperCheckerBundle() {
    }

    public static @Nls @NotNull String message(@PropertyKey(resourceBundle = BUNDLE) @NotNull String key,
                                               Object @NotNull ... params) {
        return INSTANCE.getMessage(key, params);
    }

    public static @NotNull Supplier<@Nls String> lazy(@PropertyKey(resourceBundle = BUNDLE) @NotNull String key,
                                                       Object @NotNull ... params) {
        return INSTANCE.getLazyMessage(key, params);
    }
}
