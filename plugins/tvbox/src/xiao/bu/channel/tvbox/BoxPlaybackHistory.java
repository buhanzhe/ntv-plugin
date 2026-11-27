package xiao.bu.channel.tvbox;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class BoxPlaybackHistory {
    private static final int LIMIT = 80;

    static final class Entry {
        final String siteKey, vodId, title, line, episode;
        final int lineIndex, episodeIndex;

        Entry(String siteKey, String vodId, String title, String line, String episode, int lineIndex, int episodeIndex) {
            this.siteKey = siteKey;
            this.vodId = vodId;
            this.title = title;
            this.line = line;
            this.episode = episode;
            this.lineIndex = lineIndex;
            this.episodeIndex = episodeIndex;
        }
    }

    private final SharedPreferences preferences;
    private final List<Entry> entries = new ArrayList<>();

    BoxPlaybackHistory(Context context) {
        preferences = context.getSharedPreferences("tvbox-playback-history", Context.MODE_PRIVATE);
        try {
            JSONArray stored = new JSONArray(preferences.getString("items", "[]"));
            for (int i = 0; i < Math.min(LIMIT, stored.length()); i++) {
                JSONObject row = stored.optJSONObject(i);
                if (row == null) continue;
                String site = row.optString("site"), id = row.optString("id");
                if (!site.isEmpty() && !id.isEmpty())
                    entries.add(new Entry(site, id, row.optString("title"), row.optString("line"),
                            row.optString("episode"), row.optInt("lineIndex"), row.optInt("episodeIndex")));
            }
        } catch (JSONException invalid) {
            // A damaged history list does not prevent playback.
        }
    }

    boolean enabled() {
        return preferences.getBoolean("enabled", true);
    }

    void setEnabled(boolean value) {
        preferences.edit().putBoolean("enabled", value).apply();
    }

    List<Entry> list() {
        return new ArrayList<>(entries);
    }

    void clear() {
        entries.clear();
        preferences.edit().remove("items").apply();
    }

    void record(Entry entry) {
        if (!enabled()) return;
        for (Iterator<Entry> it = entries.iterator(); it.hasNext();) {
            Entry saved = it.next();
            if (saved.siteKey.equals(entry.siteKey) && saved.vodId.equals(entry.vodId)) it.remove();
        }
        entries.add(0, entry);
        if (entries.size() > LIMIT) entries.remove(entries.size() - 1);
        JSONArray result = new JSONArray();
        for (Entry saved : entries) {
            try {
                result.put(new JSONObject().put("site", saved.siteKey).put("id", saved.vodId)
                        .put("title", saved.title).put("line", saved.line).put("episode", saved.episode)
                        .put("lineIndex", saved.lineIndex).put("episodeIndex", saved.episodeIndex));
            } catch (JSONException invalid) {
                throw new IllegalStateException(invalid);
            }
        }
        preferences.edit().putString("items", result.toString()).apply();
    }
}
