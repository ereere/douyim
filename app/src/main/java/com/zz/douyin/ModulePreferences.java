package com.zz.douyin;

import android.content.SharedPreferences;

public final class ModulePreferences {
    public static final String NAME = "content_filter";
    public static final String KEY_IMMERSIVE_ENABLED = "immersive_enabled";

    private ModulePreferences() {
    }

    public static boolean readImmersiveEnabled(SharedPreferences preferences) {
        return preferences == null || preferences.getBoolean(KEY_IMMERSIVE_ENABLED, true);
    }
}
