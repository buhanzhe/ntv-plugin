package com.github.catvod.spider;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import com.github.catvod.crawler.Spider;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercise the legacy two-dialog login flow with an isolated test credential. */
public final class CloudLoginFixture extends Spider {
    private SharedPreferences preferences;

    @Override public void init(Context context, String extend) {
        preferences = context.getSharedPreferences("spUtils", Context.MODE_PRIVATE);
    }

    @Override public String homeContent(boolean filter) throws Exception {
        return new JSONObject().put("list", new JSONArray()
                .put(new JSONObject().put("vod_id", "fixture-login").put("vod_name", "登入自己网盘")
                        .put("vod_tag", "action"))
                .put(new JSONObject().put("vod_id", "fixture-cleanup").put("vod_name", "清理扫码测试")
                        .put("vod_tag", "action"))).toString();
    }

    @Override public String action(String action) {
        if ("fixture-cleanup".equals(action)) {
            preferences.edit().remove("quark_fixture_cookie").apply();
            return "{}";
        }
        if (!"fixture-login".equals(action)) return "{}";
        Activity activity = getActivity();
        activity.runOnUiThread(() -> {
            boolean loggedIn = preferences.contains("quark_fixture_cookie");
            new AlertDialog.Builder(activity)
                    .setTitle("已登录+启用中(可进入和搜索自己盘)")
                    .setItems(new String[]{"我的夸父- " + (loggedIn ? "已登录" : "未登录")},
                            (parent, which) -> { })
                    .setNegativeButton("取消", null)
                    .create().show();
            // A real QR window closes after credentials are committed, leaving the parent open.
            if (!loggedIn) {
                AlertDialog qr = new AlertDialog.Builder(activity).setTitle("测试二维码")
                        .setPositiveButton("测试授权", (dialog, which) -> preferences.edit()
                                .putString("quark_fixture_cookie", "fixture").apply())
                        .setNegativeButton("取消", null).create();
                qr.show();
            }
        });
        return "{}";
    }
}
