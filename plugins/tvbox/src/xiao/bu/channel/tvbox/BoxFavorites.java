package xiao.bu.channel.tvbox;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class BoxFavorites {
    static final class Entry {
        final String siteKey, siteName;
        final JSONObject item;

        Entry(String siteKey, String siteName, JSONObject item) {
            this.siteKey = siteKey;
            this.siteName = siteName;
            this.item = item;
        }
    }

    private final SharedPreferences preferences;
    private final List<Entry> entries = new ArrayList<>();

    BoxFavorites(Context context) {
        preferences = context.getSharedPreferences("tvbox-favorites", Context.MODE_PRIVATE);
        try {
            JSONArray saved = new JSONArray(preferences.getString("items", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject record = saved.optJSONObject(i);
                if (record == null) continue;
                JSONObject item = record.optJSONObject("item");
                String key = record.optString("site");
                if (item != null && !key.isEmpty() && !item.optString("vod_id").isEmpty()) {
                    entries.add(new Entry(key, record.optString("name"), item));
                }
            }
        } catch (JSONException invalid) {
            // A damaged entry list does not prevent site browsing.
        }
    }

    List<Entry> list() {
        return new ArrayList<>(entries);
    }

    Entry find(String siteKey, String id) {
        for (Entry entry : entries) {
            if (entry.siteKey.equals(siteKey) && entry.item.optString("vod_id").equals(id)) return entry;
        }
        return null;
    }

    boolean toggle(String siteKey, String siteName, String vodId, String title) {
        Entry entry = find(siteKey, vodId);
        if (entry != null) {
            entries.remove(entry);
            save();
            return false;
        }
        try {
            JSONObject saved = new JSONObject().put("vod_id", vodId).put("vod_name", title);
            entries.add(0, new Entry(siteKey, siteName, saved));
            save();
            return true;
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("收藏条目无效", invalid);
        }
    }

    private void save() {
        JSONArray saved = new JSONArray();
        for (Entry entry : entries) {
            try {
                saved.put(new JSONObject().put("site", entry.siteKey)
                        .put("name", entry.siteName).put("item", entry.item));
            } catch (JSONException invalid) {
                throw new IllegalStateException(invalid);
            }
        }
        preferences.edit().putString("items", saved.toString()).apply();
    }
}
