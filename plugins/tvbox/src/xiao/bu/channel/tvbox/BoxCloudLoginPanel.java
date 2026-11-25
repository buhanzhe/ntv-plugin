package xiao.bu.channel.tvbox;

import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The CatVod login action draws its own dialog from a snapshot of the account state. */
final class BoxCloudLoginPanel {
    private static final String PANEL_TITLE = "可进入和搜索自己盘";
    private static final String QUARK_ROW = "我的夸父";
    private static final long FOCUS_TIMEOUT_MS = 3500;

    static void refresh(View host, Handler main, Runnable reopen, Check current) {
        View panel = null;
        for (View window : windows()) {
            if (window == host.getRootView() || window.getWindowToken() == null || !window.isShown()) continue;
            if (findText(window, PANEL_TITLE) && hasQuarkStatus(window, "未登录")) {
                panel = window;
                break;
            }
        }
        if (panel != null) waitForFocus(panel, main, reopen, current);
    }

    interface Check {
        boolean isCurrent();
    }

    private static void waitForFocus(View panel, Handler main, Runnable reopen, Check current) {
        long until = SystemClock.uptimeMillis() + FOCUS_TIMEOUT_MS;
        main.post(new Runnable() {
            @Override public void run() {
                if (!current.isCurrent() || panel.getWindowToken() == null || !panel.isShown()) return;
                if (!panel.hasWindowFocus()) {
                    if (SystemClock.uptimeMillis() < until) main.postDelayed(this, 100);
                    return;
                }
                if (!hasQuarkStatus(panel, "未登录")) return;
                // Dispatch Back to this dialog only, never to the Activity or the QR window.
                long time = SystemClock.uptimeMillis();
                panel.dispatchKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0));
                panel.dispatchKeyEvent(new KeyEvent(time, SystemClock.uptimeMillis(),
                        KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0));
                long detachUntil = SystemClock.uptimeMillis() + 1000;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (!current.isCurrent()) return;
                        if (panel.getWindowToken() == null) reopen.run();
                        else if (SystemClock.uptimeMillis() < detachUntil) main.postDelayed(this, 50);
                    }
                });
            }
        });
    }

    private static boolean hasQuarkStatus(View view, String status) {
        return findText(view, QUARK_ROW + status);
    }

    private static boolean findText(View view, String expected) {
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString().replace(" ", "").replace("-", "");
            if (text.contains(expected)) return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup children = (ViewGroup) view;
            for (int i = 0; i < children.getChildCount(); i++) {
                if (findText(children.getChildAt(i), expected)) return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<View> windows() {
        if (Build.VERSION.SDK_INT >= 29) return Api29.windows();
        if (Build.VERSION.SDK_INT < 17 || Build.VERSION.SDK_INT >= 28) return Collections.emptyList();
        try {
            Class<?> type = Class.forName("android.view.WindowManagerGlobal");
            Object manager = type.getMethod("getInstance").invoke(null);
            Field views = type.getDeclaredField("mViews");
            views.setAccessible(true);
            return new ArrayList<>((List<View>) views.get(manager));
        } catch (Exception | LinkageError unavailable) {
            return Collections.emptyList();
        }
    }

    private static final class Api29 {
        static List<View> windows() {
            return android.view.inspector.WindowInspector.getGlobalWindowViews();
        }
    }
}
