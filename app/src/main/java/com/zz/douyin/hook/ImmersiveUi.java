package com.zz.douyin.hook;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class ImmersiveUi {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long SCAN_INTERVAL_MS = 100L;
    // Step the floating button at random intervals so it never burns into the
    // panel: 10-20s between steps, half a button height per step.
    private static final long DRIFT_MIN_INTERVAL_MS = 10_000L;
    private static final long DRIFT_MAX_INTERVAL_MS = 20_000L;
    private static final Map<View, SavedView> HIDDEN =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<View> GESTURE_PATHS =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<View, Integer> ROOT_SYSTEM_UI =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, ViewGroup.LayoutParams> EXPANDED_VIEWPORTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ViewTreeObserver.OnPreDrawListener HIDDEN_VIEW_GUARD =
            () -> {
                reassertHiddenViews();
                return true;
            };
    private static WeakReference<View> hiddenGuardRoot = new WeakReference<>(null);
    private static volatile boolean immersiveEnabled = true;
    private static SharedPreferences immersivePreferences;
    private static final SharedPreferences.OnSharedPreferenceChangeListener
            PREFERENCE_LISTENER = (preferences, key) -> refreshPreferences(preferences);

    private static WeakReference<Activity> active = new WeakReference<>(null);
    private static WeakReference<View> activeRoot = new WeakReference<>(null);
    private static boolean scanScheduled;
    private static boolean activityResolveErrorLogged;
    private static long lastScanFailureAt;
    private static boolean hadVideo;
    // User's persistent mode chosen with the floating button:
    // AUTO follows content type (video -> immersive, non-video -> normal),
    // EXITED stays out of immersive mode on every video until the user
    // presses "进", ENTERED stays immersive on every video (non-video
    // content still shows normally) until the user presses "退".
    private static final int MODE_AUTO = 0;
    private static final int MODE_EXITED = 1;
    private static final int MODE_ENTERED = 2;
    private static int userMode = MODE_AUTO;
    // Temporary exit for the center long-press native menu. A feed swipe
    // clears it; the persistent userMode is untouched.
    private static boolean menuExit;

    private static boolean immersiveWantedNow() {
        return immersiveEnabled && userMode != MODE_EXITED && !menuExit;
    }

    // Raw touch tracking: vertical feed swipes and center long-press.
    private static float touchDownX;
    private static float touchDownY;
    private static long touchGestureToken;
    private static boolean touchInProgress;
    private static Runnable pendingLongPress;
    private static int touchSlop = 24;
    private static int longPressTimeout = 400;

    private static WeakReference<TextView> toggleButton = new WeakReference<>(null);
    private static float buttonOffsetY;
    private static int driftDirection = 1;
    private static final Runnable BURN_DRIFT = new Runnable() {
        @Override
        public void run() {
            stepToggleButton();
            MAIN.postDelayed(this, randomDriftInterval());
        }
    };

    private static long randomDriftInterval() {
        return DRIFT_MIN_INTERVAL_MS
                + (long) (Math.random() * (DRIFT_MAX_INTERVAL_MS - DRIFT_MIN_INTERVAL_MS));
    }

    private ImmersiveUi() {
    }

    static synchronized void configurePreferences(SharedPreferences preferences) {
        if (immersivePreferences != null && immersivePreferences != preferences) {
            try {
                immersivePreferences.unregisterOnSharedPreferenceChangeListener(
                        PREFERENCE_LISTENER
                );
            } catch (RuntimeException ignored) {
                // A dead remote preference bridge will be replaced below.
            }
        }
        immersivePreferences = preferences;
        refreshPreferences(preferences);
        if (preferences != null) {
            preferences.registerOnSharedPreferenceChangeListener(PREFERENCE_LISTENER);
        }
    }

    private static void refreshPreferences(SharedPreferences preferences) {
        boolean enabled = com.zz.douyin.ModulePreferences.readImmersiveEnabled(preferences);
        boolean changed = immersiveEnabled != enabled;
        immersiveEnabled = enabled;
        MAIN.post(() -> {
            if (changed) {
                touchGestureToken++;
                touchInProgress = false;
                cancelPendingLongPress();
                userMode = MODE_AUTO;
                menuExit = false;
                Log.i(DouyinModule.TAG, "immersive enabled=" + immersiveEnabled);
            }
            if (!immersiveEnabled) {
                removeToggleButton();
                Activity activity = activeActivity();
                restoreAll(activity, activeDecor(activity));
            }
            scheduleScan(0L);
        });
    }

    static void onActivityResumed(Activity activity) {
        if (activity == null) {
            return;
        }
        MAIN.post(() -> {
            active = new WeakReference<>(activity);
            View decor = activity.getWindow().getDecorView();
            activeRoot = new WeakReference<>(decor);
            ViewConfiguration configuration = ViewConfiguration.get(activity);
            touchSlop = configuration.getScaledTouchSlop();
            longPressTimeout = configuration.getLongPressTimeout();
            attachHiddenViewGuard(decor);
            Log.i(DouyinModule.TAG, "activity resumed: " + activity.getClass().getName());
            scheduleScan(120L);
        });
    }

    static void onActivityPaused(Activity activity) {
        MAIN.post(() -> {
            Activity current = active.get();
            if (current == activity) {
                removeToggleButton();
                restoreAll(current);
                active.clear();
                activeRoot.clear();
                touchInProgress = false;
                cancelPendingLongPress();
            }
        });
    }

    static void onActivityTouch(Activity activity, MotionEvent event) {
        int action = event.getActionMasked();
        if (isInsideToggleButton(event.getRawX(), event.getRawY())) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touchInProgress = false;
                cancelPendingLongPress();
            }
            return;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            touchInProgress = true;
            touchGestureToken++;
            touchDownX = event.getRawX();
            touchDownY = event.getRawY();
            scheduleLongPressCheck(activity, event, touchGestureToken);
            return;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getRawX() - touchDownX;
            float dy = event.getRawY() - touchDownY;
            if (dx * dx + dy * dy > (float) touchSlop * touchSlop) {
                cancelPendingLongPress();
            }
            return;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            touchInProgress = false;
            cancelPendingLongPress();
            return;
        }
        if (action != MotionEvent.ACTION_UP) {
            return;
        }
        touchInProgress = false;
        cancelPendingLongPress();

        View decor = activeDecor(activity);
        float density = decor == null
                ? 1f
                : decor.getResources().getDisplayMetrics().density;
        float dx = event.getRawX() - touchDownX;
        float dy = event.getRawY() - touchDownY;
        if (decor != null
                && Math.abs(dy) > 72f * density
                && Math.abs(dy) > Math.abs(dx)) {
            // User swiped to another feed: only clear the temporary native
            // menu exit. The persistent button mode (退/进) is untouched, and
            // the button drifts so it never stays over the same pixels.
            menuExit = false;
            driftToggleButton();
        }
    }

    private static void scheduleLongPressCheck(
            Activity activity,
            MotionEvent downEvent,
            long token
    ) {
        cancelPendingLongPress();
        // Only a center-area long-press asks Douyin for its context menu. Side
        // long-presses are the native fast-forward / rewind gestures.
        if (!isCenterArea(downEvent.getRawX(), downEvent.getRawY(), activeDecor(activity))) {
            return;
        }
        Runnable runnable = () -> {
            pendingLongPress = null;
            if (token != touchGestureToken || !touchInProgress) {
                return;
            }
            // Restore the full interface so Douyin's long-press menu is visible;
            // the native gesture timer fires its popup right after this. This
            // is a temporary exit only — the next feed swipe re-arms immersive.
            menuExit = true;
            Activity current = activeActivity();
            restoreAll(current, activeDecor(current));
            showToggleButton(current, activeDecor(current), true);
            Log.i(DouyinModule.TAG, "center long-press: exit immersive for native menu");
        };
        pendingLongPress = runnable;
        MAIN.postDelayed(runnable, longPressTimeout);
    }

    private static void cancelPendingLongPress() {
        if (pendingLongPress != null) {
            MAIN.removeCallbacks(pendingLongPress);
            pendingLongPress = null;
        }
    }

    private static boolean isCenterArea(float rawX, float rawY, View decor) {
        return decor != null
                && rawX >= decor.getWidth() * 0.18f
                && rawX <= decor.getWidth() * 0.82f
                && rawY >= decor.getHeight() * 0.12f
                && rawY <= decor.getHeight() * 0.86f;
    }

    private static void scheduleScan(long delayMs) {
        if (scanScheduled) {
            return;
        }
        scanScheduled = true;
        MAIN.postDelayed(ImmersiveUi::scan, delayMs);
    }

    private static void scan() {
        scanScheduled = false;
        try {
            scanOnce();
        } catch (Throwable error) {
            long now = SystemClock.uptimeMillis();
            if (now - lastScanFailureAt >= 2_000L) {
                lastScanFailureAt = now;
                Log.e(DouyinModule.TAG,
                        "immersive UI scan failed; watchdog will retry", error);
            }
        } finally {
            if (!scanScheduled) {
                scheduleScan(SCAN_INTERVAL_MS);
            }
        }
    }

    private static void scanOnce() {
        Activity activity = activeActivity();
        View decor = activeDecor(activity);
        if (decor == null) {
            scheduleScan(250L);
            return;
        }
        if (activity != null && (activity.isFinishing() || activity.isDestroyed())) {
            return;
        }

        // Non-video content (图文 / 文字 / 文章) has a visible label. Exit
        // immersive mode so the content shows normally. When the user swipes
        // to the next video, the label disappears and immersive mode resumes.
        // Keep the toggle button visible (in "进" state) so the user can
        // still manually toggle.
        if (hasNonVideoLabel(decor)) {
            hadVideo = false;
            restoreAll(activity, decor);
            showToggleButton(activity, decor, true);
            resetToggleButtonToTop();
            scheduleScan(SCAN_INTERVAL_MS);
            return;
        }

        List<View> realVideos = findVisibleRealVideoViews(decor);
        boolean videoVisible = !realVideos.isEmpty();
        if (!videoVisible) {
            hadVideo = false;
            removeToggleButton();
            restoreAll(activity, decor);
            scheduleScan(SCAN_INTERVAL_MS);
            return;
        }

        hadVideo = true;

        // The persistent button mode decides: AUTO/ENTERED -> immersive,
        // EXITED -> stay out until the user presses "进".
        boolean immersiveWanted = immersiveWantedNow();
        showToggleButton(activity, decor, !immersiveWanted);

        if (!immersiveWanted) {
            restoreAll(activity, decor);
            scheduleScan(SCAN_INTERVAL_MS);
            return;
        }

        List<View> videos = realVideos;
        expandVideoViewport(decor, videos);
        List<View> preserved = new ArrayList<>(videos);
        View toggle = toggleButton.get();
        if (toggle != null) {
            preserved.add(toggle);
        }
        restorePreservedPaths(preserved);
        hideOutsidePreservedPaths(decor, preserved);
        updateGesturePaths(decor, videos);
        hideSystemBars(activity, decor);

        scheduleScan(SCAN_INTERVAL_MS);
    }

    private static Activity activeActivity() {
        Activity current = active.get();
        if (current != null && !current.isFinishing() && !current.isDestroyed()) {
            return current;
        }
        Activity resolved = resolveTopActivity();
        if (resolved != null) {
            active = new WeakReference<>(resolved);
            Log.i(DouyinModule.TAG, "resolved activity: " + resolved.getClass().getName());
        }
        return resolved;
    }

    private static View activeDecor(Activity activity) {
        if (activity != null) {
            View decor = activity.getWindow().getDecorView();
            activeRoot = new WeakReference<>(decor);
            return decor;
        }
        View current = activeRoot.get();
        if (current != null && current.isAttachedToWindow()) {
            return current;
        }
        View resolved = resolveLargestWindowRoot();
        if (resolved != null) {
            activeRoot = new WeakReference<>(resolved);
            Log.i(DouyinModule.TAG, "resolved window root: "
                    + resolved.getClass().getName() + " "
                    + resolved.getWidth() + "x" + resolved.getHeight());
        }
        return resolved;
    }

    private static View resolveLargestWindowRoot() {
        try {
            Class<?> globalClass = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = globalClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object global = getInstance.invoke(null);
            Field viewsField = globalClass.getDeclaredField("mViews");
            viewsField.setAccessible(true);
            if (!(viewsField.get(global) instanceof List<?> roots)) {
                return null;
            }
            View best = null;
            long bestArea = 0L;
            for (Object item : roots) {
                if (!(item instanceof View root) || !root.isAttachedToWindow()) {
                    continue;
                }
                long area = (long) root.getWidth() * root.getHeight();
                if (area > bestArea) {
                    best = root;
                    bestArea = area;
                }
            }
            return best;
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (!activityResolveErrorLogged) {
                activityResolveErrorLogged = true;
                Log.e(DouyinModule.TAG, "failed to resolve current window root", error);
            }
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Activity resolveTopActivity() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentThreadMethod =
                    activityThreadClass.getDeclaredMethod("currentActivityThread");
            currentThreadMethod.setAccessible(true);
            Object activityThread = currentThreadMethod.invoke(null);
            if (activityThread == null) {
                return null;
            }
            Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            if (!(activitiesField.get(activityThread) instanceof Map<?, ?> activities)) {
                return null;
            }
            Activity fallback = null;
            for (Object record : activities.values()) {
                if (record == null) {
                    continue;
                }
                Field activityField = record.getClass().getDeclaredField("activity");
                activityField.setAccessible(true);
                if (!(activityField.get(record) instanceof Activity candidate)
                        || candidate.isFinishing()
                        || candidate.isDestroyed()) {
                    continue;
                }
                fallback = candidate;
                try {
                    Field pausedField = record.getClass().getDeclaredField("paused");
                    pausedField.setAccessible(true);
                    if (!pausedField.getBoolean(record)) {
                        return candidate;
                    }
                } catch (NoSuchFieldException ignored) {
                    return candidate;
                }
            }
            return fallback;
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (!activityResolveErrorLogged) {
                activityResolveErrorLogged = true;
                Log.e(DouyinModule.TAG, "failed to resolve current Activity", error);
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Video surface discovery
    // ------------------------------------------------------------------

    private static List<View> findVisibleRealVideoViews(View root) {
        List<View> candidates = new ArrayList<>();
        collectRealVideoViews(root, candidates);
        List<View> visible = centeredVisibleVideoViews(root, candidates);
        visible.removeIf(view -> !hasValidRenderSurface(view));
        return visible;
    }

    private static boolean hasValidRenderSurface(View view) {
        try {
            if (view instanceof SurfaceView surfaceView) {
                return surfaceView.getHolder().getSurface() != null
                        && surfaceView.getHolder().getSurface().isValid();
            }
            if (view instanceof TextureView textureView) {
                return textureView.isAvailable()
                        && textureView.getSurfaceTexture() != null;
            }
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    // Scans the view tree for a visible on-screen non-video label: "图文",
    // "文字" or "文章". Douyin shows these labels on image-text / text /
    // article feed items, so finding one means the current content is not a
    // video and the module should exit immersive mode to let it show normally.
    // The label is matched exactly and must be on-screen (getGlobalVisibleRect)
    // so that text from recycled/off-screen view holders doesn't trigger it.
    private static boolean hasNonVideoLabel(View root) {
        if (root instanceof TextView tv) {
            if (tv.getVisibility() == View.VISIBLE
                    && tv.getAlpha() > 0.05f) {
                CharSequence text = tv.getText();
                if (text != null && isNonVideoLabelText(text.toString())) {
                    Rect visible = new Rect();
                    if (tv.getGlobalVisibleRect(visible)
                            && visible.width() > 0
                            && visible.height() > 0) {
                        return true;
                    }
                }
            }
        }
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasNonVideoLabel(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isNonVideoLabelText(String value) {
        String trimmed = value.trim();
        return "图文".equals(trimmed)
                || "文字".equals(trimmed)
                || "文章".equals(trimmed);
    }

    private static List<View> centeredVisibleVideoViews(View root, List<View> candidates) {
        Rect rootVisible = new Rect();
        if (!root.getGlobalVisibleRect(rootVisible)) {
            return Collections.emptyList();
        }
        int centerX = rootVisible.centerX();
        int centerY = rootVisible.centerY();
        long screenArea =
                (long) Math.max(1, rootVisible.width()) * Math.max(1, rootVisible.height());
        List<View> result = new ArrayList<>();
        Rect visible = new Rect();
        for (View candidate : candidates) {
            boolean alphaVisible =
                    candidate.getAlpha() > 0.05f || HIDDEN.containsKey(candidate);
            if (!candidate.isAttachedToWindow()
                    || candidate.getVisibility() != View.VISIBLE
                    || !candidate.isShown()
                    || !alphaVisible
                    || !candidate.getGlobalVisibleRect(visible)
                    || !visible.contains(centerX, centerY)) {
                continue;
            }
            long area = (long) visible.width() * visible.height();
            if (area >= screenArea / 5L && !result.contains(candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static void collectRealVideoViews(View view, List<View> out) {
        if (view instanceof SurfaceView || view instanceof TextureView) {
            out.add(view);
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectRealVideoViews(group.getChildAt(i), out);
            }
        }
    }

    // ------------------------------------------------------------------
    // Viewport expansion
    // ------------------------------------------------------------------

    private static void expandVideoViewport(View decor, List<View> videos) {
        for (View video : videos) {
            if (expandVideoViewportFrom(decor, video)) {
                return;
            }
        }
    }

    private static boolean expandVideoViewportFrom(View decor, View video) {
        View viewport = video;
        while (viewport.getParent() instanceof View parent && parent != decor) {
            boolean targetViewport = "com.ss.android.ugc.aweme.common.widget.RTViewPager"
                    .equals(viewport.getClass().getName());
            int missingHeight = parent.getHeight() - viewport.getHeight();
            boolean parentFillsWindow =
                    parent.getWidth() >= decor.getWidth() * 0.9f
                            && parent.getHeight() >= decor.getHeight() * 0.95f;
            boolean viewportLeavesBottomSlot =
                    viewport.getWidth() >= decor.getWidth() * 0.9f
                            && missingHeight > 32
                            && missingHeight < decor.getHeight() / 3;
            if (targetViewport && parentFillsWindow && viewportLeavesBottomSlot) {
                applyExpandedViewport(viewport);
                return true;
            }
            viewport = parent;
        }
        return false;
    }

    private static void applyExpandedViewport(View viewport) {
        if (EXPANDED_VIEWPORTS.containsKey(viewport)) {
            return;
        }
        ViewGroup.LayoutParams original = viewport.getLayoutParams();
        if (!(original instanceof RelativeLayout.LayoutParams relative)) {
            Log.w(DouyinModule.TAG,
                    "RTViewPager layout not RelativeLayout.LayoutParams: "
                            + original.getClass().getName());
            return;
        }
        RelativeLayout.LayoutParams expanded = new RelativeLayout.LayoutParams(relative);
        expanded.height = ViewGroup.LayoutParams.MATCH_PARENT;
        expanded.topMargin = 0;
        expanded.bottomMargin = 0;
        expanded.removeRule(RelativeLayout.ABOVE);
        expanded.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        expanded.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        EXPANDED_VIEWPORTS.put(viewport, original);
        viewport.setLayoutParams(expanded);
        viewport.requestLayout();
    }

    // ------------------------------------------------------------------
    // Hide / restore views
    // ------------------------------------------------------------------

    private static boolean overlapsAnyVideo(View candidate, List<View> videos) {
        Rect candidateRect = new Rect();
        if (!candidate.getGlobalVisibleRect(candidateRect) || candidateRect.isEmpty()) {
            return false;
        }
        Rect videoRect = new Rect();
        for (View video : videos) {
            if (video.getGlobalVisibleRect(videoRect)
                    && Rect.intersects(candidateRect, videoRect)) {
                return true;
            }
        }
        return false;
    }

    private static void hideOutsidePreservedPaths(View node, List<View> preservedViews) {
        if (preservedViews.contains(node)) {
            return;
        }
        if (!(node instanceof ViewGroup group)
                || !containsAny(group, preservedViews)) {
            hide(node);
            return;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (preservedViews.contains(child)
                    || containsAny(child, preservedViews)) {
                hideOutsidePreservedPaths(child, preservedViews);
            } else {
                hide(child);
            }
        }
    }

    private static boolean containsAny(View node, List<View> targets) {
        for (View target : targets) {
            if (contains(node, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(View node, View target) {
        if (node == target) {
            return true;
        }
        if (node instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (contains(group.getChildAt(i), target)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void restorePreservedPaths(List<View> preservedViews) {
        synchronized (HIDDEN) {
            for (Map.Entry<View, SavedView> entry :
                    new ArrayList<>(HIDDEN.entrySet())) {
                View view = entry.getKey();
                SavedView saved = entry.getValue();
                if (view == null || saved == null
                        || (!preservedViews.contains(view)
                        && !containsAny(view, preservedViews))) {
                    continue;
                }
                view.setAlpha(saved.alpha);
                view.setVisibility(saved.visibility);
                view.setImportantForAccessibility(saved.accessibility);
                HIDDEN.remove(view);
            }
        }
    }

    private static void hide(View view) {
        if (!HIDDEN.containsKey(view)) {
            HIDDEN.put(view, new SavedView(
                    view.getAlpha(),
                    view.getImportantForAccessibility(),
                    view.getVisibility()
            ));
        }
        view.setAlpha(0f);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private static boolean hasHiddenViews() {
        synchronized (HIDDEN) {
            return !HIDDEN.isEmpty();
        }
    }

    private static void updateGesturePaths(View root, List<View> videos) {
        GESTURE_PATHS.clear();
        collectGesturePaths(root, root, videos);
    }

    private static void collectGesturePaths(View node, View root, List<View> videos) {
        if (ImmersiveTouchPolicy.isGestureView(node.getClass().getName())
                && node.isAttachedToWindow()
                && node.getVisibility() == View.VISIBLE
                && node.getWidth() >= root.getWidth() * 0.8f
                && node.getHeight() >= root.getHeight() * 0.5f
                && overlapsAnyVideo(node, videos)) {
            View path = node;
            while (path != null) {
                GESTURE_PATHS.add(path);
                path = path.getParent() instanceof View parent ? parent : null;
            }
        }
        if (node instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectGesturePaths(group.getChildAt(i), root, videos);
            }
        }
    }

    static boolean shouldBlockHiddenTouch(View view) {
        if (!immersiveWantedNow() || !hasHiddenViews()) {
            return false;
        }
        boolean hidden = false;
        View path = view;
        while (path != null) {
            if (HIDDEN.containsKey(path)) {
                hidden = true;
                break;
            }
            path = path.getParent() instanceof View parent ? parent : null;
        }
        return ImmersiveTouchPolicy.shouldBlock(hidden, GESTURE_PATHS.contains(view));
    }

    private static void reassertHiddenViews() {
        if (!immersiveWantedNow()) return;
        synchronized (HIDDEN) {
            for (Map.Entry<View, SavedView> entry :
                    new ArrayList<>(HIDDEN.entrySet())) {
                View view = entry.getKey();
                if (view != null && view.isAttachedToWindow()
                        && view.getAlpha() != 0f) {
                    view.setAlpha(0f);
                }
            }
        }
    }

    private static void attachHiddenViewGuard(View root) {
        View previous = hiddenGuardRoot.get();
        if (previous == root) {
            return;
        }
        detachHiddenViewGuard();
        if (root == null) {
            return;
        }
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.addOnPreDrawListener(HIDDEN_VIEW_GUARD);
            hiddenGuardRoot = new WeakReference<>(root);
        }
    }

    private static void detachHiddenViewGuard() {
        View root = hiddenGuardRoot.get();
        hiddenGuardRoot.clear();
        if (root == null) {
            return;
        }
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnPreDrawListener(HIDDEN_VIEW_GUARD);
        }
    }

    private static void restoreAll(Activity activity) {
        restoreAll(activity, activeDecor(activity), true);
    }

    private static void restoreAll(Activity activity, View decor) {
        restoreAll(activity, decor, false);
    }

    private static void restoreAll(Activity activity, View decor, boolean leavingActivity) {
        if (leavingActivity) {
            detachHiddenViewGuard();
        }
        restoreExpandedViewports();
        int restored = 0;
        synchronized (HIDDEN) {
            for (Map.Entry<View, SavedView> entry : new ArrayList<>(HIDDEN.entrySet())) {
                View view = entry.getKey();
                SavedView saved = entry.getValue();
                if (view != null && saved != null) {
                    view.setAlpha(saved.alpha);
                    view.setVisibility(saved.visibility);
                    view.setImportantForAccessibility(saved.accessibility);
                    restored++;
                }
            }
            HIDDEN.clear();
            GESTURE_PATHS.clear();
        }
        if (restored > 0) {
            Log.d(DouyinModule.TAG, "restored hidden views=" + restored);
        }
        if (decor != null) {
            Integer systemUi = ROOT_SYSTEM_UI.remove(decor);
            if (systemUi != null) {
                decor.setSystemUiVisibility(systemUi);
            }
        }
    }

    private static void restoreExpandedViewports() {
        synchronized (EXPANDED_VIEWPORTS) {
            for (Map.Entry<View, ViewGroup.LayoutParams> entry :
                    new ArrayList<>(EXPANDED_VIEWPORTS.entrySet())) {
                View view = entry.getKey();
                ViewGroup.LayoutParams original = entry.getValue();
                if (view != null && original != null) {
                    view.setLayoutParams(original);
                    view.requestLayout();
                }
            }
            EXPANDED_VIEWPORTS.clear();
        }
    }

    private static void hideSystemBars(Activity activity, View decor) {
        if (!ROOT_SYSTEM_UI.containsKey(decor)) {
            ROOT_SYSTEM_UI.put(decor, decor.getSystemUiVisibility());
        }
        decor.setSystemUiVisibility(
                decor.getSystemUiVisibility()
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    // ------------------------------------------------------------------
    // Floating toggle button
    // ------------------------------------------------------------------

    private static void showToggleButton(Activity activity, View decor, boolean exited) {
        if (activity == null
                || decor == null
                || !(decor instanceof FrameLayout container)) {
            removeToggleButton();
            return;
        }

        TextView current = toggleButton.get();
        if (current != null && current.getParent() != container) {
            removeToggleButton();
            current = null;
        }
        if (current == null) {
            int size = dp(decor, 40);
            current = new TextView(activity);
            // 5% opacity text: the glyph is barely visible to avoid burn-in.
            current.setTextColor(0x0DFFFFFF);
            current.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            current.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            current.setGravity(Gravity.CENTER);
            current.setIncludeFontPadding(false);
            current.setClickable(true);
            current.setFocusable(true);
            current.setElevation(dp(decor, 6));

            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(0x26000000);
            background.setStroke(dp(decor, 1), 0x33FFFFFF);
            current.setBackground(background);
            current.setOnClickListener(view -> onToggleClicked());

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    size, size, Gravity.TOP | Gravity.END
            );
            // Keep a two-button-height gap from the top.
            params.topMargin = size * 2;
            params.rightMargin = dp(decor, 12);
            container.addView(current, params);
            toggleButton = new WeakReference<>(current);
            Log.i(DouyinModule.TAG, "immersive toggle button shown");

            buttonOffsetY = 0f;
            driftDirection = 1;
            final TextView created = current;
            created.post(() -> created.setTranslationY(0f));
            restartDriftTimer();
        }

        current.setText(exited ? "进" : "退");
        current.setContentDescription(exited ? "进入沉浸式播放" : "退出沉浸式播放");
        current.setVisibility(View.VISIBLE);
        current.bringToFront();
    }

    private static void removeToggleButton() {
        MAIN.removeCallbacks(BURN_DRIFT);
        TextView button = toggleButton.get();
        if (button != null) {
            button.animate().cancel();
            HIDDEN.remove(button);
            button.setOnClickListener(null);
            if (button.getParent() instanceof ViewGroup parent) {
                parent.removeView(button);
            }
        }
        toggleButton.clear();
    }

    /** One immediate step after a feed switch, then restart the random timer. */
    private static void driftToggleButton() {
        stepToggleButton();
        restartDriftTimer();
    }

    private static void restartDriftTimer() {
        MAIN.removeCallbacks(BURN_DRIFT);
        MAIN.postDelayed(BURN_DRIFT, randomDriftInterval());
    }

    /**
     * Moves the button half its own height in the current direction. It walks
     * downward step by step until the bottom bound (one third of the screen),
     * then reverses and walks back up to the top (ping-pong). The button only
     * ever moves within the top third of the screen.
     */
    private static void stepToggleButton() {
        TextView button = toggleButton.get();
        if (button == null || !button.isAttachedToWindow()
                || !(button.getParent() instanceof View decor)) {
            return;
        }
        // The button's bottom edge must not pass the one-third screen line.
        float maxOffset = decor.getHeight() / 3f
                - button.getTop() - button.getHeight();
        if (maxOffset <= 0f) {
            return;
        }
        buttonOffsetY += button.getHeight() / 2f * driftDirection;
        if (buttonOffsetY >= maxOffset) {
            buttonOffsetY = maxOffset;
            driftDirection = -1;
        } else if (buttonOffsetY <= 0f) {
            buttonOffsetY = 0f;
            driftDirection = 1;
        }
        button.animate().translationY(buttonOffsetY).setDuration(300).start();
    }

    // Resets the button to its resting spot: two button heights from the top.
    // Used when immersive mode auto-exits for non-video (图文/文字/文章) content.
    private static void resetToggleButtonToTop() {
        buttonOffsetY = 0f;
        driftDirection = 1;
        TextView button = toggleButton.get();
        if (button != null && button.isAttachedToWindow()) {
            button.animate().translationY(0f).setDuration(300).start();
        }
    }

    private static boolean isInsideToggleButton(float rawX, float rawY) {
        TextView button = toggleButton.get();
        if (button == null || !button.isAttachedToWindow()) {
            return false;
        }
        int[] location = new int[2];
        button.getLocationOnScreen(location);
        return rawX >= location[0]
                && rawX <= location[0] + button.getWidth()
                && rawY >= location[1]
                && rawY <= location[1] + button.getHeight();
    }

    private static void onToggleClicked() {
        Activity activity = activeActivity();
        if (immersiveWantedNow() && hadVideo) {
            // 退: persistent exit. Every later video stays in normal mode
            // until the user presses "进" again.
            userMode = MODE_EXITED;
            menuExit = false;
            restoreAll(activity, activeDecor(activity));
            showToggleButton(activity, activeDecor(activity), true);
            Log.i(DouyinModule.TAG, "toggle: persistent exit immersive playback");
        } else {
            // 进: persistent enter. Every later video goes immersive (the
            // next scan hides the host UI); non-video content still shows
            // normally until then.
            userMode = MODE_ENTERED;
            menuExit = false;
            Log.i(DouyinModule.TAG, "toggle: persistent enter immersive playback");
        }
        scheduleScan(0L);
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static final class SavedView {
        final float alpha;
        final int accessibility;
        final int visibility;

        SavedView(float alpha, int accessibility, int visibility) {
            this.alpha = alpha;
            this.accessibility = accessibility;
            this.visibility = visibility;
        }
    }
}
