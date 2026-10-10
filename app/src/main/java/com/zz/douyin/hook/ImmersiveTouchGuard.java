package com.zz.douyin.hook;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

final class ImmersiveTouchGuard {
    private ImmersiveTouchGuard() {
    }

    static void install(DouyinModule module) throws NoSuchMethodException {
        // Filter before child.dispatchTouchEvent(), including custom View overrides and
        // children of transparent containers. Returning false lets the video layer and
        // the native feed gesture receiver (tap / swipe / long-press menu) get the touch.
        Method dispatchChild = ViewGroup.class.getDeclaredMethod(
                "dispatchTransformedTouchEvent", MotionEvent.class, boolean.class,
                View.class, int.class);
        module.hook(dispatchChild)
                .setId("douyin-immersive-hidden-touch")
                .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    MotionEvent event = (MotionEvent) chain.getArgs().get(0);
                    int action = event.getActionMasked();
                    View child = (View) chain.getArgs().get(2);
                    if ((action == MotionEvent.ACTION_DOWN
                            || action == MotionEvent.ACTION_POINTER_DOWN)
                            && child != null
                            && ImmersiveUi.shouldBlockHiddenTouch(child)) {
                        return false;
                    }
                    return chain.proceed();
                });
    }
}
