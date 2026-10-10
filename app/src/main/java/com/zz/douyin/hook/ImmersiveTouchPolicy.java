package com.zz.douyin.hook;

final class ImmersiveTouchPolicy {
    static final String GESTURE_VIEW = "com.ss.android.ugc.aweme.feed.ui.LongPressLayout";

    private ImmersiveTouchPolicy() {
    }

    static boolean isGestureView(String className) {
        return GESTURE_VIEW.equals(className);
    }

    static boolean shouldBlock(boolean hidden, boolean gesturePath) {
        return hidden && !gesturePath;
    }
}
