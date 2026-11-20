package xiao.bu.channel.tvbox;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import okhttp3.OkHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;
import xiao.bu.tv.jar.ChannelPlugin;

public final class TvBoxPlugin implements ChannelPlugin, ChannelPlugin.NativePage {
    private interface Load { Runnable run() throws Exception; }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Runnable> history = new ArrayList<>();
    private Context context;
    private ChannelPlugin.VodHost host;
    private BoxSource source;
    private LinearLayout root, actions, rows;
    private TextView heading, status;
    private ScrollView scroll;
    private Runnable screen;
    private Future<?> pending;
    private volatile boolean disposed;
    private AlertDialog trust;
    private int generation;

    public View create(Context context, ClassLoader resources, JSONObject config, Host base) throws Exception {
        if (!(base instanceof ChannelPlugin.VodHost)) throw new IOException("请更新 nTv 以运行 TVBox");
        this.context = context;
        host = (ChannelPlugin.VodHost) base;
        source = new BoxSource(context, host, resources, new BoxHttp((OkHttpClient) host.httpClient()));
        root = new LinearLayout(context) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (super.dispatchKeyEvent(event)) return true;
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int direction;
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_UP: direction = View.FOCUS_UP; break;
                    case KeyEvent.KEYCODE_DPAD_DOWN: direction = View.FOCUS_DOWN; break;
                    case KeyEvent.KEYCODE_DPAD_LEFT: direction = View.FOCUS_LEFT; break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: direction = View.FOCUS_RIGHT; break;
                    default: return false;
                }
                View focused = findFocus();
                if (focused == null) return requestFocus(direction);
                View next = android.view.FocusFinder.getInstance().findNextFocus(this, focused, direction);
                if (next != null) next.requestFocus(direction);
                return true;
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(18), dp(28), dp(16));
        root.setBackgroundColor(0xff101923);
        heading = text(25, 0xfff1f6fb);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(heading);
        actions = new LinearLayout(context);
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        status = text(15, 0xffbbcede);
        status.setPadding(0, dp(6), 0, dp(6));
        root.addView(status);
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        String url = config.optString("url");
        show(() -> {
            begin("TVBox");
            EditText input = editor("TVBox 配置地址", url);
            actions.addView(input, new LinearLayout.LayoutParams(0, dp(52), 1));
            button(actions, "加载配置", () -> {
                hideKeyboard(input);
                openRepository(input.getText().toString().trim(), true);
            });
        }, false);
        if (!url.isEmpty()) openRepository(url, true);
        return root;
    }

    private void openRepository(String url, boolean push) {
        load(() -> {
            source.load(url);
            List<BoxSource.Site> sites = source.sites();
            List<BoxSource.Repository> repos = source.repositories();
            return () -> show(() -> {
                begin("TVBox 站点");
                for (BoxSource.Repository repo : repos) button(rows, repo.name, () -> openRepository(repo.url, true));
                for (BoxSource.Site site : sites) button(rows, site.name, () -> select(site));
                if (sites.isEmpty() && repos.isEmpty()) status.setText("配置里没有可用站点");
            }, push);
        });
    }

    private void select(BoxSource.Site site) {
        if (!site.executable()) {
            openSite(site);
            return;
        }
        trust = new AlertDialog.Builder(context).setTitle(site.name)
                .setMessage("此站点会执行配置提供的爬虫代码。仅在信任配置和站点时继续。")
                .setNegativeButton("取消", null).setPositiveButton("加载站点", (dialog, which) -> openSite(site)).create();
        trust.setOnDismissListener(dialog -> trust = null);
        trust.show();
    }

    private void openSite(BoxSource.Site site) {
        load(() -> {
            source.select(site);
            JSONObject home = source.home();
            JSONArray data = home.optJSONArray("list");
            if (data == null || data.length() == 0) data = source.recommendations().optJSONArray("list");
            JSONArray videos = data;
            return () -> show(() -> {
                begin(site.name);
                searchBar(site);
                JSONArray categories = home.optJSONArray("class");
                if (categories != null) for (int i = 0; i < Math.min(120, categories.length()); i++) {
                    JSONObject item = categories.optJSONObject(i);
                    if (item == null || item.optString("type_id").isEmpty()) continue;
                    String id = item.optString("type_id"), name = item.optString("type_name", id);
                    button(rows, name, () -> listing(site, name, id, "", 1, true));
                }
                addVideos(site, videos);
            }, true);
        });
    }

    private void searchBar(BoxSource.Site site) {
        EditText input = editor("输入片名", "");
        actions.addView(input, new LinearLayout.LayoutParams(0, dp(52), 1));
        Runnable search = () -> {
            String word = input.getText().toString().trim();
            if (word.isEmpty()) return;
            hideKeyboard(input);
            listing(site, "搜索 · " + word, "", word, 1, true);
        };
        input.setOnEditorActionListener((view, id, event) -> {
            if (id != EditorInfo.IME_ACTION_SEARCH) return false;
            search.run(); return true;
        });
        button(actions, "搜索", search);
    }

    private void listing(BoxSource.Site site, String label, String type, String search, int page, boolean push) {
        load(() -> {
            JSONObject pageData = search.isEmpty() ? source.category(type, page, new JSONObject()) : source.search(search, page);
            JSONArray items = pageData.optJSONArray("list");
            int pages = pageData.optInt("pagecount", items == null || items.length() == 0 ? page : page + 1);
            return () -> show(() -> {
                begin(label);
                if (page > 1) button(actions, "上一页", () -> listing(site, label, type, search, page - 1, false));
                if (page < pages) button(actions, "下一页", () -> listing(site, label, type, search, page + 1, false));
                status.setText(items != null && items.length() > 0 ? "第 " + page + " 页" : "暂无影片");
                addVideos(site, items);
            }, push);
        });
    }

    private void addVideos(BoxSource.Site site, JSONArray items) {
        if (items == null) return;
        for (int i = 0; i < Math.min(items.length(), 160); i++) {
            JSONObject video = items.optJSONObject(i);
            if (video == null || video.optString("vod_id").isEmpty()) continue;
            String id = video.optString("vod_id"), title = video.optString("vod_name", id);
            String remarks = video.optString("vod_remarks");
            button(rows, remarks.isEmpty() ? title : title + "  ·  " + remarks, () -> detail(site, id, title));
        }
    }

    private void detail(BoxSource.Site site, String id, String title) {
        load(() -> {
            JSONArray models = source.detail(id).optJSONArray("list");
            JSONObject video = models == null ? null : models.optJSONObject(0);
            if (video == null) throw new IOException("站点没有返回影片详情");
            String[] flags = video.optString("vod_play_from").split("\\$\\$\\$", -1);
            String[] routes = video.optString("vod_play_url").split("\\$\\$\\$", -1);
            return () -> show(() -> {
                begin(video.optString("vod_name", title));
                String description = video.optString("vod_content").replaceAll("<[^>]+>", "").trim();
                if (!description.isEmpty()) {
                    TextView overview = text(16, 0xffbbcede);
                    overview.setText(description);
                    overview.setMaxLines(5);
                    rows.addView(overview);
                }
                int episodes = 0;
                for (int i = 0; i < Math.min(24, routes.length); i++) {
                    if (routes[i].isEmpty()) continue;
                    String flag = i < flags.length ? flags[i] : "";
                    TextView route = text(18, 0xff9de9df);
                    route.setText(flag.isEmpty() ? "播放线路" : flag);
                    route.setPadding(0, dp(12), 0, dp(4));
                    rows.addView(route);
                    for (String entry : routes[i].split("#")) {
                        if (entry.isEmpty()) continue;
                        int delimiter = entry.indexOf('$');
                        String name = delimiter < 0 ? "播放" : entry.substring(0, delimiter);
                        String mediaId = delimiter < 0 ? entry : entry.substring(delimiter + 1);
                        button(rows, name, () -> play(flag, mediaId));
                        if (++episodes >= 500) return;
                    }
                }
                if (episodes == 0) status.setText("站点没有提供播放线路");
            }, true);
        });
    }

    private void play(String flag, String id) {
        load(() -> {
            JSONObject result = source.play(flag, id);
            JSONObject headers = result.optJSONObject("header");
            if (headers == null && result.opt("header") instanceof String)
                headers = new JSONObject(result.optString("header"));
            JSONObject mediaHeaders = headers;
            return () -> host.playVod(result.optString("url"), mediaHeaders);
        });
    }

    private void load(Load operation) {
        if (disposed || pending != null) return;
        int request = ++generation;
        status.setText("正在加载…");
        pending = worker.submit(() -> {
            try {
                Runnable result = operation.run();
                main.post(() -> {
                    if (disposed || generation != request) return;
                    pending = null;
                    status.setText("");
                    result.run();
                });
            } catch (Exception | LinkageError error) {
                main.post(() -> {
                    if (disposed || generation != request) return;
                    pending = null;
                    String detail = error instanceof IOException ? error.getMessage() : error.getClass().getSimpleName();
                    status.setText("加载失败 · " + (detail == null ? "请检查站点" : detail));
                });
            }
        });
    }

    private void show(Runnable render, boolean push) {
        if (push && screen != null) history.add(screen);
        screen = render;
        render.run();
        scroll.scrollTo(0, 0);
        View first = rows.getChildCount() > 0 ? rows.getChildAt(0) : actions.getChildAt(actions.getChildCount() - 1);
        if (first != null && !first.requestFocus()) root.requestFocus();
    }

    private void begin(String title) {
        heading.setText(title);
        status.setText("");
        actions.removeAllViews();
        rows.removeAllViews();
        if (!history.isEmpty()) button(actions, "返回", this::onBack);
    }

    private EditText editor(String hint, String value) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setText(value);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xff9ab1c4);
        input.setSingleLine();
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        return input;
    }

    private void hideKeyboard(View input) {
        ((InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private TextView text(int size, int color) {
        TextView view = new TextView(context);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void button(LinearLayout parent, String title, Runnable click) {
        Button button = new Button(context);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(17);
        button.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xff101923, 0xffe9f2f8}));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, card(0xff9de9df));
        background.addState(new int[]{android.R.attr.state_pressed}, card(0xff316f79));
        background.addState(new int[0], card(0xff263542));
        button.setBackgroundDrawable(background);
        button.setGravity(Gravity.CENTER_VERTICAL | (parent == rows ? Gravity.LEFT : Gravity.CENTER_HORIZONTAL));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setSingleLine();
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams dimensions = new LinearLayout.LayoutParams(parent == rows ? -1 : -2, dp(48));
        dimensions.setMargins(0, dp(4), parent == rows ? 0 : dp(8), dp(4));
        parent.addView(button, dimensions);
        button.setOnClickListener(view -> click.run());
    }

    private GradientDrawable card(int color) {
        GradientDrawable style = new GradientDrawable();
        style.setColor(color);
        style.setCornerRadius(dp(7));
        return style;
    }

    private int dp(int value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }

    public boolean onBack() {
        if (pending != null) {
            generation++;
            pending.cancel(true);
            pending = null;
            status.setText("");
            return true;
        }
        if (history.isEmpty()) return false;
        show(history.remove(history.size() - 1), false);
        return true;
    }

    public void setActive(boolean active) { }
    public void update(long positionMs, boolean playing) { }
    public void dispose() {
        if (disposed) return;
        disposed = true;
        if (trust != null) trust.dismiss();
        if (pending != null) pending.cancel(true);
        worker.execute(() -> { if (source != null) source.close(); });
        worker.shutdown();
    }
}
