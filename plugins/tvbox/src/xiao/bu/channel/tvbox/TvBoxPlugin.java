package xiao.bu.channel.tvbox;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import okhttp3.OkHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;
import xiao.bu.channel.bl.BlTile;
import xiao.bu.channel.bl.BlUi;
import xiao.bu.tv.jar.ChannelPlugin;

public final class TvBoxPlugin implements ChannelPlugin, ChannelPlugin.NativePage {
    private static final int VIDEO_COLUMNS = 4;
    private static final int MAX_HISTORY = 16;

    private enum Tab {
        SITES("站点", android.R.drawable.ic_menu_agenda),
        HOME("推荐", android.R.drawable.ic_menu_view),
        CATEGORIES("分类", android.R.drawable.ic_menu_sort_by_size),
        SEARCH("搜索", android.R.drawable.ic_menu_search),
        CONFIG("配置", android.R.drawable.ic_menu_preferences);

        final String label;
        final int icon;
        Tab(String label, int icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private interface Load { Runnable run() throws Exception; }
    private interface Submit { void accept(String text); }

    private static final class Screen {
        final Tab tab;
        final String title;
        final boolean landing;
        final Runnable render;
        int selection;

        Screen(Tab tab, String title, boolean landing, Runnable render) {
            this.tab = tab;
            this.title = title;
            this.landing = landing;
            this.render = render;
        }
    }

    private static final class Choice {
        final String title;
        final Runnable action;

        Choice(String title, Runnable action) {
            this.title = title;
            this.action = action;
        }
    }

    private static final class SiteHome {
        final BoxSource.Site site;
        final JSONArray categories, videos;
        final String notice;

        SiteHome(BoxSource.Site site, JSONArray categories, JSONArray videos, String notice) {
            this.site = site;
            this.categories = categories;
            this.videos = videos;
            this.notice = notice;
        }
    }

    private static final class PlayLine {
        final String name;
        final List<Choice> episodes = new ArrayList<>();

        PlayLine(String name) {
            this.name = name;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<Tab, Button> navigation = new EnumMap<>(Tab.class);
    private final ArrayDeque<Screen> history = new ArrayDeque<>();
    private BlUi ui;
    private BoxArt art;
    private Context context;
    private VodHost host;
    private BoxSource source;
    private LinearLayout root, body, actions;
    private TextView heading, status;
    private GridView grid;
    private View primary;
    private Screen screen, stations;
    private SiteHome currentSite;
    private String configUrl;
    private Future<?> pending;
    private volatile boolean disposed;
    private int generation;

    @Override public View create(Context context, ClassLoader assets, JSONObject config, Host base) throws Exception {
        if (!(base instanceof VodHost)) throw new IOException("请更新 nTv 以运行 TVBox");
        host = (VodHost) base;
        this.context = context;
        ui = new BlUi(context);
        BoxHttp http = new BoxHttp((OkHttpClient) host.httpClient());
        source = new BoxSource(context, host, assets, http);
        art = new BoxArt(http);
        root = new LinearLayout(context) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (super.dispatchKeyEvent(event)) return true;
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int direction = focusDirection(event.getKeyCode());
                if (direction == 0) return false;
                View next = FocusFinder.getInstance().findNextFocus(this, findFocus(), direction);
                return next != null && next.requestFocus(direction);
            }
        };
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff302733, 0xff201c24}));
        root.addView(sidebar(), new LinearLayout.LayoutParams(ui.px(108), -1));
        LinearLayout page = ui.vertical();
        page.setPadding(ui.px(22), ui.px(16), ui.px(22), ui.px(12));
        root.addView(page, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout header = ui.horizontal();
        heading = ui.title("");
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(heading, new LinearLayout.LayoutParams(0, ui.px(50), 1));
        actions = ui.horizontal();
        header.addView(actions, new LinearLayout.LayoutParams(-2, -2));
        page.addView(header);
        status = ui.text("", 16, BlUi.SUB);
        status.setMaxLines(2);
        status.setPadding(0, ui.px(6), 0, ui.px(6));
        page.addView(status);
        body = ui.vertical();
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        configUrl = config.optString("url");
        show(configScreen(), false);
        if (!configUrl.isEmpty()) openRepository(configUrl, true);
        return root;
    }

    private LinearLayout sidebar() {
        LinearLayout bar = ui.vertical();
        bar.setPadding(ui.px(9), ui.px(14), ui.px(9), ui.px(12));
        bar.setBackgroundColor(0x66201b23);
        TextView brand = ui.title("TVBox");
        brand.setTextColor(BlUi.ACCENT);
        brand.setGravity(Gravity.CENTER);
        bar.addView(brand, new LinearLayout.LayoutParams(-1, ui.px(56)));
        for (Tab tab : Tab.values()) {
            Button button = ui.button(tab.label);
            button.setAllCaps(false);
            button.setPadding(ui.px(3), ui.px(5), ui.px(3), ui.px(5));
            Drawable icon = context.getResources().getDrawable(tab.icon).mutate();
            icon.setColorFilter(BlUi.TEXT, PorterDuff.Mode.SRC_IN);
            icon.setBounds(0, 0, ui.px(24), ui.px(24));
            button.setCompoundDrawables(null, icon, null, null);
            button.setCompoundDrawablePadding(ui.px(3));
            button.setOnClickListener(view -> switchTab(tab));
            button.setOnKeyListener((view, code, event) -> {
                if (code != KeyEvent.KEYCODE_DPAD_RIGHT || primary == null) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) primary.requestFocus();
                return true;
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, ui.px(66));
            size.bottomMargin = ui.px(5);
            if (tab == Tab.CONFIG) bar.addView(new View(context), new LinearLayout.LayoutParams(1, 0, 1));
            bar.addView(button, size);
            navigation.put(tab, button);
        }
        return bar;
    }

    private void switchTab(Tab tab) {
        if (pending != null || screen.tab == tab && screen.landing) return;
        if (currentSite == null && tab != Tab.SITES && tab != Tab.CONFIG) return;
        switch (tab) {
            case CONFIG:
                show(configScreen(), true);
                break;
            case SITES:
                if (stations == null) show(configScreen(), false);
                else {
                    while (!history.isEmpty() && history.peek() != stations) history.pop();
                    if (!history.isEmpty()) history.pop();
                    show(stations, false);
                }
                break;
            case HOME:
                show(homeScreen(currentSite), true);
                break;
            case CATEGORIES:
                show(categoryScreen(currentSite), true);
                break;
            case SEARCH:
                show(searchScreen(currentSite), true);
                break;
        }
    }

    private Screen configScreen() {
        return new Screen(Tab.CONFIG, "配置站点", true, () -> {
            EditText input = form("TVBox 配置地址", configUrl, "加载配置", url -> {
                if (!url.isEmpty()) openRepository(url, true);
            });
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        });
    }

    private void openRepository(String url, boolean topLevel) {
        load(() -> {
            source.load(url);
            List<BoxSource.Site> sites = source.sites();
            List<BoxSource.Repository> repos = source.repositories();
            return () -> {
                if (topLevel) {
                    history.clear();
                    configUrl = url;
                }
                currentSite = null;
                Screen next = new Screen(Tab.SITES, "TVBox 站点", true, () -> {
                    List<Choice> choices = new ArrayList<>();
                    for (BoxSource.Repository repo : repos) {
                        choices.add(new Choice(repo.name, () -> openRepository(repo.url, false)));
                    }
                    for (BoxSource.Site site : sites) choices.add(new Choice(site.name, () -> openSite(site)));
                    choices(body, choices, 3, 70);
                    if (choices.isEmpty()) message("配置里没有可用站点");
                });
                show(next, true);
            };
        });
    }

    private void openSite(BoxSource.Site site) {
        load(() -> {
            source.select(site);
            JSONObject home = source.home();
            JSONArray categories = home.optJSONArray("class");
            JSONArray videos = home.optJSONArray("list");
            String notice = "";
            if (!hasVideos(videos)) {
                try { videos = source.recommendations().optJSONArray("list"); }
                catch (Exception | LinkageError error) {
                    notice = "推荐加载失败 · " + error.getClass().getSimpleName();
                }
            }
            if (!hasVideos(videos) && categories != null) {
                for (int i = 0; i < Math.min(120, categories.length()); i++) {
                    JSONObject category = categories.optJSONObject(i);
                    if (category == null || category.optString("type_id").isEmpty()) continue;
                    String id = category.optString("type_id");
                    try {
                        videos = source.category(id, 1, new JSONObject()).optJSONArray("list");
                        if (hasVideos(videos)) notice = "分类 · " + category.optString("type_name", id);
                    } catch (Exception | LinkageError error) {
                        notice = "分类加载失败 · " + error.getClass().getSimpleName();
                    }
                    break;
                }
            }
            SiteHome model = new SiteHome(site, categories, videos, notice);
            return () -> {
                currentSite = model;
                show(homeScreen(model), true);
            };
        });
    }

    private Screen homeScreen(SiteHome model) {
        return new Screen(Tab.HOME, model.site.name, true, () -> {
            message(model.notice);
            if (!videos(model.site, model.videos)) {
                if (model.notice.isEmpty()) message("站点暂无推荐影片");
                if (model.categories != null && model.categories.length() > 0) {
                    Button categories = action(body, "浏览分类", () -> switchTab(Tab.CATEGORIES));
                    primary = categories;
                } else {
                    primary = action(body, "搜索影片", () -> switchTab(Tab.SEARCH));
                }
            }
        });
    }

    private static boolean hasVideos(JSONArray items) {
        for (int i = 0; items != null && i < Math.min(160, items.length()); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && !item.optString("vod_id").isEmpty()) return true;
        }
        return false;
    }

    private Screen categoryScreen(SiteHome model) {
        return new Screen(Tab.CATEGORIES, "影片分类", true, () -> {
            List<Choice> values = new ArrayList<>();
            JSONArray categories = model.categories;
            for (int i = 0; categories != null && i < Math.min(120, categories.length()); i++) {
                JSONObject row = categories.optJSONObject(i);
                if (row == null || row.optString("type_id").isEmpty()) continue;
                String id = row.optString("type_id"), name = row.optString("type_name", id);
                values.add(new Choice(name, () -> listing(model.site, name, id, "", 1, true)));
            }
            choices(body, values, 4, 58);
            if (values.isEmpty()) message("站点没有提供影片分类");
        });
    }

    private Screen searchScreen(SiteHome model) {
        String[] query = {""};
        return new Screen(Tab.SEARCH, "搜索影片", true, () -> {
            EditText input = form("输入片名", query[0], "开始搜索", text -> {
                if (text.isEmpty()) return;
                query[0] = text;
                listing(model.site, "搜索 · " + text, "", text, 1, true);
            });
            GridView keyboard = ui.keyboard(input);
            addGrid(body, keyboard, 10);
        });
    }

    private EditText form(String hint, String value, String label, Submit submit) {
        LinearLayout row = ui.horizontal();
        row.setPadding(0, ui.px(16), 0, ui.px(12));
        EditText input = ui.editor(hint, value);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        row.addView(input, new LinearLayout.LayoutParams(0, ui.px(52), 1));
        ui.gap(row, 10);
        Runnable enter = () -> {
            InputMethodManager keyboard = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
            submit.accept(input.getText().toString().trim());
        };
        action(row, label, enter);
        body.addView(row);
        input.setOnEditorActionListener((view, id, event) -> {
            if (id != EditorInfo.IME_ACTION_SEARCH) return false;
            enter.run();
            return true;
        });
        primary = input;
        return input;
    }

    private void listing(BoxSource.Site site, String label, String type, String search, int page, boolean push) {
        Tab tab = search.isEmpty() ? Tab.CATEGORIES : Tab.SEARCH;
        load(() -> {
            source.select(site);
            JSONObject result = search.isEmpty() ? source.category(type, page, new JSONObject())
                    : source.search(search, page);
            JSONArray items = result.optJSONArray("list");
            int pages = result.optInt("pagecount", items == null || items.length() == 0 ? page : page + 1);
            return () -> show(new Screen(tab, label, false, () -> {
                if (page > 1) action(actions, "上一页", () -> listing(site, label, type, search, page - 1, false));
                if (page < pages) action(actions, "下一页", () -> listing(site, label, type, search, page + 1, false));
                message(videos(site, items) ? "第 " + page + " 页" : "暂无影片");
            }), push);
        });
    }

    private boolean videos(BoxSource.Site site, JSONArray items) {
        List<JSONObject> videos = new ArrayList<>();
        for (int i = 0; items != null && i < Math.min(160, items.length()); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && !item.optString("vod_id").isEmpty()) videos.add(item);
        }
        if (videos.isEmpty()) return false;
        GridView cards = ui.grid(VIDEO_COLUMNS);
        cards.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return videos.size(); }
            @Override public JSONObject getItem(int position) { return videos.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View reusable, ViewGroup parent) {
                JSONObject item = getItem(position);
                BlTile tile = reusable instanceof BlTile ? (BlTile) reusable : new BlTile(ui);
                ImageView cover = tile.bind(videoName(item), metadata(item, "vod_year", "vod_area", "vod_class"),
                        BlUi.plain(item.optString("vod_remarks")));
                art.load(cover, artwork(site, item));
                return tile;
            }
        });
        cards.setOnItemClickListener((parent, view, index, id) -> {
            screen.selection = index;
            JSONObject item = videos.get(index);
            String command = item.optString("action");
            if (command.isEmpty() && "action".equals(item.optString("vod_tag"))) command = item.optString("vod_id");
            if (command.isEmpty()) detail(site, item);
            else runSiteAction(site, command);
        });
        addGrid(body, cards, VIDEO_COLUMNS);
        return true;
    }

    private void runSiteAction(BoxSource.Site site, String command) {
        load(() -> {
            source.select(site);
            JSONObject result = source.action(command);
            String text = result.optString("toast");
            return () -> message(text);
        });
    }

    private void detail(BoxSource.Site site, JSONObject item) {
        Tab from = screen.tab;
        load(() -> {
            source.select(site);
            JSONArray list = source.detail(item.optString("vod_id")).optJSONArray("list");
            JSONObject video = list == null ? null : list.optJSONObject(0);
            if (video == null) throw new IOException("站点没有返回影片详情");
            String title = BlUi.plain(video.optString("vod_name", videoName(item)));
            String cover = artwork(site, video);
            String image = cover.isEmpty() ? artwork(site, item) : cover;
            List<PlayLine> lines = playLines(site, video);
            int[] selected = {0};
            return () -> show(new Screen(from, title, false, () -> {
                summary(video, image);
                if (lines.isEmpty()) message("站点没有提供播放线路");
                else episodePanel(lines, selected);
            }), true);
        });
    }

    private void summary(JSONObject video, String image) {
        LinearLayout hero = ui.horizontal();
        hero.setGravity(Gravity.TOP);
        hero.setPadding(0, ui.px(16), 0, ui.px(14));
        ImageView cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.load(cover, image);
        hero.addView(cover, new LinearLayout.LayoutParams(ui.px(250), ui.px(141)));
        ui.gap(hero, 24);
        LinearLayout info = ui.vertical();
        hero.addView(info, new LinearLayout.LayoutParams(0, ui.px(141), 1));
        String facts = metadata(video, "vod_year", "vod_area", "vod_class", "vod_remarks");
        if (!facts.isEmpty()) info.addView(detailText(facts, 19, BlUi.TEXT, 1));
        String cast = metadata(video, "vod_director", "vod_actor");
        if (!cast.isEmpty()) info.addView(detailText(cast, 16, BlUi.SUB, 1));
        String synopsis = BlUi.plain(video.optString("vod_content")).trim();
        if (!synopsis.isEmpty()) info.addView(detailText(synopsis, 17, BlUi.SUB, 4));
        body.addView(hero);
    }

    private TextView detailText(String text, int size, int color, int lines) {
        TextView view = ui.text(text, size, color);
        view.setMaxLines(lines);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setPadding(0, 0, 0, ui.px(8));
        return view;
    }

    private List<PlayLine> playLines(BoxSource.Site site, JSONObject video) {
        String[] flags = video.optString("vod_play_from").split("\\$\\$\\$", -1);
        String[] routes = video.optString("vod_play_url").split("\\$\\$\\$", -1);
        List<PlayLine> lines = new ArrayList<>();
        int episodeCount = 0;
        for (int i = 0; i < Math.min(24, routes.length) && episodeCount < 500; i++) {
            String flag = i < flags.length ? flags[i] : "";
            PlayLine line = new PlayLine(flag.isEmpty() ? "线路 " + (i + 1) : flag);
            for (String entry : routes[i].split("#")) {
                if (entry.isEmpty()) continue;
                int delimiter = entry.indexOf('$');
                String name = delimiter < 0 ? "播放" : entry.substring(0, delimiter);
                String id = delimiter < 0 ? entry : entry.substring(delimiter + 1);
                line.episodes.add(new Choice(name, () -> play(site, flag, id)));
                if (++episodeCount >= 500) break;
            }
            if (!line.episodes.isEmpty()) lines.add(line);
        }
        return lines;
    }

    private void episodePanel(List<PlayLine> lines, int[] selected) {
        LinearLayout channels = ui.horizontal();
        LinearLayout panel = ui.vertical();
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(channels);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        body.addView(panel, new LinearLayout.LayoutParams(-1, 0, 1));
        for (int i = 0; i < lines.size(); i++) {
            int index = i;
            Button option = action(channels, lines.get(i).name, () -> {
                if (pending != null) return;
                selected[0] = index;
                screen.selection = 0;
                for (int j = 0; j < channels.getChildCount(); j++) {
                    channels.getChildAt(j).setSelected(j == index);
                }
                panel.removeAllViews();
                choices(panel, lines.get(index).episodes, 6, 48);
                focusContent();
            });
            option.setSelected(i == selected[0]);
        }
        choices(panel, lines.get(selected[0]).episodes, 6, 48);
    }

    private void play(BoxSource.Site site, String flag, String id) {
        load(() -> {
            source.select(site);
            JSONObject result = source.play(flag, id);
            JSONObject headers = result.optJSONObject("header");
            if (headers == null && result.opt("header") instanceof String) {
                headers = new JSONObject(result.optString("header"));
            }
            JSONObject mediaHeaders = headers;
            return () -> host.playVod(result.optString("url"), mediaHeaders);
        });
    }

    private void choices(LinearLayout parent, List<Choice> items, int columns, int height) {
        if (items.isEmpty()) return;
        GridView options = ui.grid(columns);
        options.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Choice getItem(int position) { return items.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View reuse, ViewGroup container) {
                TextView item = reuse instanceof TextView ? (TextView) reuse : ui.text("", 18, BlUi.TEXT);
                if (reuse == null) {
                    item.setGravity(Gravity.CENTER);
                    item.setPadding(ui.px(8), ui.px(6), ui.px(8), ui.px(6));
                    item.setMinHeight(ui.px(height));
                    item.setMaxLines(2);
                    item.setEllipsize(TextUtils.TruncateAt.END);
                    item.setBackgroundDrawable(ui.fill(0xff3b323f, 10));
                }
                item.setText(getItem(position).title);
                return item;
            }
        });
        options.setOnItemClickListener((container, view, index, id) -> {
            screen.selection = index;
            items.get(index).action.run();
        });
        addGrid(parent, options, columns);
    }

    private void addGrid(LinearLayout parent, GridView content, int columns) {
        grid = content;
        primary = grid;
        content.setOnKeyListener((view, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_LEFT || Math.max(0, content.getSelectedItemPosition()) % columns != 0) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) navigation.get(screen.tab).requestFocus();
            return true;
        });
        parent.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private Button action(LinearLayout parent, String label, Runnable run) {
        Button button = ui.button(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> run.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
        size.rightMargin = ui.px(8);
        parent.addView(button, size);
        return button;
    }

    private void show(Screen next, boolean push) {
        if (push && screen != null) {
            saveSelection();
            history.push(screen);
            if (history.size() > MAX_HISTORY) history.removeLast();
        }
        screen = next;
        if (next.tab == Tab.SITES) stations = next;
        heading.setText(next.title);
        message("");
        actions.removeAllViews();
        body.removeAllViews();
        grid = null;
        primary = null;
        boolean browsing = next.tab != Tab.CONFIG && next.tab != Tab.SITES;
        for (Map.Entry<Tab, Button> entry : navigation.entrySet()) {
            Tab tab = entry.getKey();
            entry.getValue().setVisibility(browsing || tab == Tab.SITES || tab == Tab.CONFIG ? View.VISIBLE : View.GONE);
            entry.getValue().setSelected(tab == next.tab);
        }
        if (!history.isEmpty()) action(actions, "返回", this::onBack);
        next.render.run();
        focusContent();
    }

    private void saveSelection() {
        if (grid != null && grid.getSelectedItemPosition() >= 0) {
            screen.selection = grid.getSelectedItemPosition();
        }
    }

    private void focusContent() {
        if (grid != null && grid.getCount() > 0) {
            grid.requestFocusFromTouch();
            grid.setSelection(Math.min(screen.selection, grid.getCount() - 1));
        } else {
            navigation.get(screen.tab).requestFocus();
        }
    }

    private void load(Load operation) {
        if (disposed || pending != null) return;
        int request = ++generation;
        message("正在加载…");
        pending = worker.submit(() -> {
            try {
                Runnable result = operation.run();
                main.post(() -> {
                    if (disposed || generation != request) return;
                    pending = null;
                    message("");
                    result.run();
                });
            } catch (Exception | LinkageError error) {
                main.post(() -> {
                    if (disposed || generation != request) return;
                    pending = null;
                    String detail = error instanceof IOException ? error.getMessage() : error.getClass().getSimpleName();
                    message("加载失败 · " + (detail == null ? "请检查站点" : detail));
                });
            }
        });
    }

    private void message(String text) {
        status.setText(text);
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static String videoName(JSONObject video) {
        return BlUi.plain(video.optString("vod_name", video.optString("vod_id")));
    }

    private static String metadata(JSONObject item, String... keys) {
        List<String> values = new ArrayList<>();
        for (String key : keys) {
            String value = BlUi.plain(item.optString(key)).trim();
            if (!value.isEmpty()) values.add(value);
        }
        return TextUtils.join("  ·  ", values);
    }

    private static String artwork(BoxSource.Site site, JSONObject item) {
        String picture = item.optString("vod_pic").trim();
        if (picture.isEmpty()) return "";
        try {
            String origin = site.type == 3 ? site.origin : BoxHttp.resolve(site.origin, site.api);
            return BoxHttp.resolve(origin, picture);
        } catch (IOException invalid) {
            return "";
        }
    }

    private static int focusDirection(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return View.FOCUS_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return View.FOCUS_RIGHT;
            default: return 0;
        }
    }

    @Override public boolean onBack() {
        if (pending != null) {
            generation++;
            pending.cancel(true);
            pending = null;
            message("");
            return true;
        }
        if (history.isEmpty()) return false;
        show(history.pop(), false);
        return true;
    }

    @Override public void setActive(boolean active) { }
    @Override public void update(long positionMs, boolean playing) { }

    @Override public void dispose() {
        if (disposed) return;
        disposed = true;
        if (pending != null) pending.cancel(true);
        if (art != null) art.close();
        main.removeCallbacksAndMessages(null);
        worker.execute(() -> { if (source != null) source.close(); });
        worker.shutdown();
    }
}
