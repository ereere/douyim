package com.zz.douyin.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ImmersiveTouchPolicyTest {
    @Test
    public void hiddenControlsAreBlockedButGesturePathStaysTouchable() {
        assertTrue(ImmersiveTouchPolicy.shouldBlock(true, false));
        assertFalse(ImmersiveTouchPolicy.shouldBlock(true, true));
        assertFalse(ImmersiveTouchPolicy.shouldBlock(false, false));
        assertFalse(ImmersiveTouchPolicy.shouldBlock(false, true));
    }

    @Test
    public void onlyTheNativeFeedGestureReceiverIsAllowed() {
        assertTrue(ImmersiveTouchPolicy.isGestureView(
                "com.ss.android.ugc.aweme.feed.ui.LongPressLayout"));
        assertFalse(ImmersiveTouchPolicy.isGestureView(
                "com.ss.android.ugc.aweme.common.widget.DiggLayout"));
        assertFalse(ImmersiveTouchPolicy.isGestureView(null));
    }
}
