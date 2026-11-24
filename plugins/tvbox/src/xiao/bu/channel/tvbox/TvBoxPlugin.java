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
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import okhttp3.OkHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;
import xiao.bu.channel.bl.BlUi;
import xiao.bu.tv.jar.ChannelPlugin;

public final class TvBoxPlugin implements ChannelPlugin, ChannelPlugin.NativePage {
    private static final int VIDEO_COLUMNS = 4;
    private static final int MAX_HISTORY = 16;

    private enum Tab {
        SITES("站点", android.R.drawable.ic_menu_agenda),
        HOME("影视", android.R.drawable.ic_menu_view),
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
        SearchRun search;

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
        final JSONObject filters;
        final String notice;
        final long created = android.os.SystemClock.elapsedRealtime();

        SiteHome(BoxSource.Site site, JSONArray categories, JSONArray videos, JSONObject filters, String notice) {
            this.site = site;
            this.categories = categories;
            this.videos = videos;
            this.filters = filters;
            this.notice = notice;
        }
    }

    private static final class SearchHit {
        final BoxSource.Site site; final JSONObject item;
        SearchHit(BoxSource.Site site, JSONObject item) { this.site = site; this.item = item; }
    }
    private static final class SearchRun {
        final String query;
        final List<BoxSource.Site> sites, failures = new ArrayList<>();
        final List<SearchHit> hits = new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        final Set<BoxSource> sessions = Collections.synchronizedSet(new HashSet<BoxSource>());
        SearchFanout<BoxSource.Site, JSONArray> fanout;
        BaseAdapter adapter;
        Button stop, retry;
        int completed;
        volatile boolean stopped;
        boolean finished, timedOut;
        SearchRun(String query, List<BoxSource.Site> sites) { this.query = query; this.sites = sites; }
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
    private final int searchConcurrency = android.os.Build.VERSION.SDK_INT < 21 ? 2 : 3;
    private final ThreadPoolExecutor searches = new ThreadPoolExecutor(searchConcurrency, searchConcurrency,
            20, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(6));
    private final Map<String, SiteHome> homes = new LinkedHashMap<String, SiteHome>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, SiteHome> entry) { return size() > 8; }
    };
    private final Map<Tab, Button> navigation = new EnumMap<>(Tab.class);
    private final ArrayDeque<Screen> history = new ArrayDeque<>();
    private final Map<String, JSONObject> selectedFilters = new HashMap<>();
    private BoxUi ui;
    private BoxArt art;
    private Context context;
    private VodHost host;
    private BoxSource source;
    private BoxHttp http;
    private FrameLayout container;
    private ImageView backdropArt;
    private ImageView ambientArt;
    private View backdropShade;
    private BoxGlass sidebarGlass;
    private String backdropUrl="";
    private int backdropGeneration;
    private BoxDashPlayer dashPlayer;
    private BoxSniffer sniffer;
    private LinearLayout root, body, actions;
    private TextView heading, status;
    private HorizontalScrollView categoryTabs;
    private LinearLayout categoryButtons;
    private GridView grid;
    private View primary;
    private Screen screen, stations;
    private SiteHome currentSite;
    private String configUrl;
    private Future<?> pending;
    private volatile boolean disposed;
    private int generation;
    private android.content.SharedPreferences authorizationPreferences;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener authorizationListener;
    private long authorizationFeedbackUntil;

    @Override public View create(Context context, ClassLoader assets, JSONObject config, Host base) throws Exception {
        if (!(base instanceof VodHost)) throw new IOException("请更新 nTv 以运行 TVBox");
        host = (VodHost) base;
        this.context = context;
        ui = new BoxUi(context,(OkHttpClient)host.httpClient());
        http = new BoxHttp((OkHttpClient) host.httpClient());
        source = new BoxSource(context, host, assets, http);
        art = new BoxArt(http);
        container = new FrameLayout(context);container.setBackgroundColor(0xff000000);
        container.setBackgroundDrawable(ui.backdrop());
        ambientArt=new ImageView(context);ambientArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        container.addView(ambientArt,new FrameLayout.LayoutParams(-1,-1));
        backdropArt = new BoxBackdrop(context,0);backdropArt.setVisibility(View.GONE);
        container.addView(backdropArt,new FrameLayout.LayoutParams(-1,-1));
        backdropShade = new View(context);
        backdropShade.setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xee061019,0xaa061019,0x19061019}));
        backdropShade.setVisibility(View.GONE);container.addView(backdropShade,new FrameLayout.LayoutParams(-1,-1));
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
        root.setBaselineAligned(false);
        root.setBackgroundColor(0x00000000);
        root.setPadding(ui.px(24), ui.px(24), ui.px(12), ui.px(24));
        boolean compact = context.getResources().getDisplayMetrics().widthPixels < ui.px(960);
        root.addView(sidebar(), new LinearLayout.LayoutParams(ui.px(compact ? 138 : 170), -1));
        LinearLayout page = ui.vertical();
        page.setPadding(ui.px(26), ui.px(4), ui.px(16), 0);
        root.addView(page, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout header = ui.horizontal();
        heading = ui.title("");
        heading.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.px(30));
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(heading, new LinearLayout.LayoutParams(0, ui.px(50), 1));
        categoryTabs = new HorizontalScrollView(context);
        categoryTabs.setHorizontalScrollBarEnabled(false);
        categoryButtons = ui.horizontal();
        categoryTabs.addView(categoryButtons);
        categoryTabs.setVisibility(View.GONE);
        header.addView(categoryTabs, new LinearLayout.LayoutParams(0, ui.px(50), 1));
        actions = ui.horizontal();
        header.addView(actions, new LinearLayout.LayoutParams(-2, -2));
        TextView clock = ui.text("", 24, BoxUi.TEXT);
        clock.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        clock.setPadding(ui.px(12), 0, 0, 0);
        header.addView(clock, new LinearLayout.LayoutParams(ui.px(88), ui.px(50)));
        Runnable updateClock = new Runnable() {
            public void run() {
                if (disposed) return;
                clock.setText(android.text.format.DateFormat.format("HH:mm", new java.util.Date()));
                main.postDelayed(this, 30000);
            }
        };
        updateClock.run();
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
        container.addView(root, new FrameLayout.LayoutParams(-1, -1));
        sniffer = new BoxSniffer(context, container, http);
        source.setSniffer(sniffer);
        authorizationPreferences = context.getSharedPreferences("spUtils", Context.MODE_PRIVATE);
        authorizationListener = (preferences, key) -> {
            if (key == null || !key.matches("(?i)(?:quark|uc|baidu|bd|ali|aliyun).*?(?:cookie|token|_ck|_st)$")) return;
            boolean feedback = android.os.SystemClock.uptimeMillis() <= authorizationFeedbackUntil;
            authorizationFeedbackUntil = 0;
            main.post(() -> {
                if (disposed) return;
                homes.clear();
                if (!feedback) return;
                String text = "网盘授权信息已更新，关闭并重新打开登录面板可刷新状态";
                message(text);
                android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show();
            });
        };
        authorizationPreferences.registerOnSharedPreferenceChangeListener(authorizationListener);
        return container;
    }

    private LinearLayout sidebar() {
        LinearLayout bar = ui.vertical();
        bar.setPadding(ui.px(12), ui.px(30), ui.px(12), ui.px(24));
        sidebarGlass=new BoxGlass(bar,container,ui.px(28),Math.max(1,ui.px(1)));
        ui.material(sidebarGlass);
        bar.setBackgroundDrawable(sidebarGlass);
        for (Tab tab : new Tab[]{Tab.SEARCH, Tab.HOME, Tab.SITES, Tab.CATEGORIES, Tab.CONFIG}) {
            Button button = ui.button(tab.label);
            button.setBackgroundDrawable(ui.navigationFocus(button));button.setTextColor(BoxUi.TEXT);
            button.setAllCaps(false);
            button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,ui.px(26));
            button.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            button.setPadding(ui.px(14), ui.px(8), ui.px(8), ui.px(8));
            Drawable icon = ui.navigationIcon(tab.ordinal());
            icon.setColorFilter(BoxUi.TEXT, PorterDuff.Mode.SRC_IN);
            icon.setBounds(0, 0, ui.px(30), ui.px(30));
            button.setCompoundDrawables(icon, null, null, null);
            button.setCompoundDrawablePadding(ui.px(12));
            button.setOnClickListener(view -> switchTab(tab));
            button.setOnKeyListener((view, code, event) -> {
                if (code != KeyEvent.KEYCODE_DPAD_RIGHT || primary == null) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) primary.requestFocus();
                return true;
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, ui.px(80));
            size.bottomMargin = ui.px(12);
            if (tab == Tab.CONFIG) bar.addView(new View(context), new LinearLayout.LayoutParams(1, 0, 1));
            bar.addView(button, size);
            navigation.put(tab, button);
        }
        return bar;
    }

    private void switchTab(Tab tab) {
        if (pending != null || screen.tab == tab && screen.landing) return;
        if (currentSite == null && tab != Tab.SITES && tab != Tab.CONFIG && tab != Tab.SEARCH) return;
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
                homes.clear();
                currentSite = null;
                Screen next = new Screen(Tab.SITES, "站点", true, () -> {
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
        if (site.ext.isEmpty() && site.name.contains("请勿") && site.name.contains("广告")) {
            SiteHome notice = new SiteHome(site, null, null, null, site.name);
            currentSite = notice;
            show(new Screen(Tab.HOME, site.name, true, () -> message(site.name)), true);
            return;
        }
        SiteHome cached = homes.get(siteKey(site));
        if (cached != null && android.os.SystemClock.elapsedRealtime() - cached.created < 120000) {
            load(() -> { source.select(site); return () -> { currentSite = cached; show(homeScreen(cached), true); }; });
            return;
        }
        load(() -> {
            source.select(site);
            JSONObject home;
            String notice = "";
            try { home = source.home(); }
            catch (Exception | LinkageError error) {
                home = new JSONObject();
                notice = "首页加载失败，可尝试搜索";
            }
            JSONArray categories = home.optJSONArray("class");
            JSONArray videos = home.optJSONArray("list");
            JSONObject filters = home.optJSONObject("filters");
            if (!hasVideos(videos)) {
                try { videos = source.recommendations().optJSONArray("list"); }
                catch (Exception | LinkageError error) {
                    notice = "推荐加载失败 · " + error.getClass().getSimpleName();
                }
            }
            if (!hasVideos(videos) && categories != null) {
                for (int i = 0; i < Math.min(3, categories.length()); i++) {
                    JSONObject category = categories.optJSONObject(i);
                    if (category == null || category.optString("type_id").isEmpty()) continue;
                    String id = category.optString("type_id");
                    try {
                        videos = source.category(id, 1, filterValues(site, id, filters)).optJSONArray("list");
                        if (hasVideos(videos)) {
                            notice = "分类 · " + category.optString("type_name", id);
                            break;
                        }
                    } catch (Exception | LinkageError error) {
                        notice = "分类加载失败 · " + error.getClass().getSimpleName();
                    }
                }
            }
            SiteHome model = new SiteHome(site, categories, videos, filters, notice);
            return () -> {
                currentSite = model;
                homes.put(siteKey(site), model);
                show(homeScreen(model), true);
            };
        });
    }
    private static String siteKey(BoxSource.Site site) { return site.origin + "\n" + site.key + "\n" + site.api; }

    private Screen homeScreen(SiteHome model) {
        return new Screen(Tab.HOME, model.site.name, true, () -> {
            Button refresh=action(actions,"↻",()->{homes.remove(siteKey(model.site));openSite(model.site);});refresh.setContentDescription("刷新站点");
            message(model.notice);
            if (!homeVideos(model)) {
                if ("push_agent".equals(model.site.key) || model.site.api.contains("PushGuard")) {
                    form("粘贴视频或网盘分享地址", "", "打开推送", address -> {
                        if (address.isEmpty()) return;
                        try { detail(model.site, new JSONObject().put("vod_id", address).put("vod_name", "手机推送")); }
                        catch (org.json.JSONException error) { message("推送地址无效"); }
                    });
                    return;
                }
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

    private boolean homeVideos(SiteHome model) {
        if (!videos(model.site, model.videos, false)) return false;
        categoryHeader(model, "");
        JSONObject featured = null;
        for (int i = 0; model.videos != null && i < Math.min(160, model.videos.length()); i++) {
            JSONObject item = model.videos.optJSONObject(i);
            if (item != null && (!item.optString("vod_id").isEmpty() || !item.optString("vod_name").isEmpty()) && item.optString("action").isEmpty()
                    && !"action".equals(item.optString("vod_tag")) && !"folder".equals(item.optString("vod_tag"))
                    && !"cate".equals(item.optString("vod_tag"))) { featured = item; break; }
        }
        TextView section = ui.title("站点推荐");
        section.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.px(28));
        section.setPadding(ui.px(4), ui.px(12), 0, 0);
        body.addView(section, 0, new LinearLayout.LayoutParams(-1, ui.px(44)));
        if (featured != null) {
            JSONObject video = featured;
            backdrop(artwork(model.site,video),true);
            int height = context.getResources().getDisplayMetrics().widthPixels < ui.px(960) ? 300 : 400;
            FrameLayout spotlight=new FrameLayout(context);
            spotlight.addView(featurePanel(model.site,video,video.optString("vod_id").isEmpty()?"搜索片名":"▶  查看选集",()->openVideo(model.site,video),true),new FrameLayout.LayoutParams(-1,-1));
            body.addView(spotlight,0,new LinearLayout.LayoutParams(-1,ui.px(height)));
            Screen owner=screen;
            main.postDelayed(new Runnable(){
                int attempts;
                public void run(){
                    if(disposed||screen!=owner||spotlight.getParent()!=body)return;
                    Drawable current=backdropArt.getDrawable();
                    if(current instanceof android.graphics.drawable.BitmapDrawable&&((android.graphics.drawable.BitmapDrawable)current).getBitmap()!=null)return;
                    for(int i=0;model.videos!=null&&i<Math.min(32,model.videos.length());i++){
                        JSONObject candidate=model.videos.optJSONObject(i);if(candidate==null||!candidate.optString("action").isEmpty()||"action".equals(candidate.optString("vod_tag"))||"folder".equals(candidate.optString("vod_tag"))||"cate".equals(candidate.optString("vod_tag")))continue;
                        String image=artwork(model.site,candidate);android.graphics.Bitmap ready=art.cached(image);if(ready==null)continue;
                        boolean focused=spotlight.hasFocus();backdrop(image,true);
                        spotlight.removeAllViews();spotlight.addView(featurePanel(model.site,candidate,candidate.optString("vod_id").isEmpty()?"搜索片名":"▶  查看选集",()->openVideo(model.site,candidate),true),new FrameLayout.LayoutParams(-1,-1));
                        if(focused&&primary!=null)primary.requestFocus();return;
                    }
                    if(++attempts<20)main.postDelayed(this,500);
                }
            },400);
        }
        return true;
    }

    private void categoryHeader(SiteHome model, String selected) {
        heading.setVisibility(View.GONE);
        categoryTabs.setVisibility(View.VISIBLE);
        Button recommended = ui.categoryButton("推荐");
        recommended.setSelected(selected.isEmpty());
        recommended.setOnClickListener(view -> { if (pending == null) show(homeScreen(model), true); });
        categoryButtons.addView(recommended, new LinearLayout.LayoutParams(-2, ui.px(50)));
        for (int i = 0; model.categories != null && i < Math.min(20, model.categories.length()); i++) {
            JSONObject item = model.categories.optJSONObject(i);
            if (item == null || item.optString("type_id").isEmpty()) continue;
            String id = item.optString("type_id"), name = item.optString("type_name", id);
            Button category = ui.categoryButton(name);
            category.setSelected(id.equals(selected));
            category.setOnClickListener(view -> listing(model.site, name, id, "", 1, true));
            categoryButtons.addView(category, new LinearLayout.LayoutParams(-2, ui.px(50)));
        }
    }

    private FrameLayout featurePanel(BoxSource.Site site, JSONObject video, String actionLabel, Runnable open) { return featurePanel(site,video,actionLabel,open,false); }
    private FrameLayout featurePanel(BoxSource.Site site, JSONObject video, String actionLabel, Runnable open, boolean fullBackdrop) {
            LinearLayout info = ui.vertical();
            FrameLayout hero = new FrameLayout(context) {
                @Override protected void onMeasure(int width, int height) {
                    info.getLayoutParams().width = Math.min(View.MeasureSpec.getSize(width), ui.px(620));
                    super.onMeasure(width, height);
                }
            };
            hero.setBackgroundDrawable(fullBackdrop?ui.fill(0,0):ui.glass(0xaa253844, 24));
            ImageView background = ui.heroImage();

            background.setBackgroundDrawable(ui.glass(0xaa253844, 24));
            if(!fullBackdrop)art.load(background, artwork(site, video));
            background.setVisibility(fullBackdrop?View.GONE:View.VISIBLE);
            hero.addView(background, new FrameLayout.LayoutParams(-1, -1));
            View fade = new View(context);
            GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xfa07121b, 0xda0b1924, 0x100b1924});
            shade.setCornerRadius(ui.px(16)); fade.setBackgroundDrawable(shade);fade.setVisibility(fullBackdrop?View.GONE:View.VISIBLE);
            hero.addView(fade, new FrameLayout.LayoutParams(-1, -1));
            info.setGravity(fullBackdrop?Gravity.TOP:Gravity.CENTER_VERTICAL);
            info.setPadding(ui.px(fullBackdrop?12:40), ui.px(fullBackdrop?60:24), ui.px(28), ui.px(24));
            String facts=metadata(video,"vod_class","vod_year","vod_remarks");
            String sourceName=BlUi.plain(site.name).replaceFirst("^[^\\p{L}\\p{N}]+","").split("[|｜丨┃│¦]",2)[0].trim();
            TextView eyebrow = ui.text(sourceName+(facts.isEmpty()?"":"  ·  "+facts), 21, BoxUi.SUB);
            eyebrow.setSingleLine(true); eyebrow.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(eyebrow, new LinearLayout.LayoutParams(-1, ui.px(30)));
            TextView title = ui.displayTitle(videoName(video));
            if(videoName(video).codePointCount(0,videoName(video).length())>10)title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,ui.px(64));
            title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(title, new LinearLayout.LayoutParams(-1, -2));
            String synopsis = BlUi.plain(video.optString("vod_content")).trim();
            TextView summary = ui.text(synopsis.isEmpty() ? metadata(video, "vod_area", "vod_class", "vod_actor") : synopsis, 22, BoxUi.TEXT);
            summary.setMaxLines(2); summary.setEllipsize(TextUtils.TruncateAt.END);
            summary.setPadding(0, ui.px(10), 0, ui.px(14));
            if (!summary.getText().toString().isEmpty()) info.addView(summary);
            LinearLayout buttons = ui.horizontal();
            Button details = action(buttons, actionLabel, open);
            details.setBackgroundDrawable(ui.primaryFocus(details)); details.setTextColor(ui.controlText());
            details.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,ui.px(26));
            details.setMinHeight(ui.px(60));details.setMinimumWidth(ui.px(220));
            Button secondary=video.optString("vod_id").isEmpty()&&currentSite!=null?action(buttons,"浏览分类",()->switchTab(Tab.CATEGORIES)):action(buttons,"搜索同名",()->chooseSearchSite(videoName(video)));
            secondary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,ui.px(26));secondary.setMinHeight(ui.px(60));secondary.setMinimumWidth(ui.px(190));
            info.addView(buttons);
            hero.addView(info, new FrameLayout.LayoutParams(-1, -1));
            primary = details;
            return hero;
    }

    private void openVideo(BoxSource.Site site, JSONObject item) {
        String itemId = item.optString("vod_id");
        if (itemId.isEmpty() || itemId.startsWith("msearch:")) {
            chooseSearchSite(itemId.startsWith("msearch:") ? itemId.substring(8) : videoName(item)); return;
        }
        String command = item.optString("action");
        if (command.isEmpty() && "action".equals(item.optString("vod_tag"))) command = itemId;
        if (!command.isEmpty()) runSiteAction(site, command);
        else if ("folder".equals(item.optString("vod_tag")) || "cate".equals(item.optString("vod_tag")))
            listing(site, videoName(item), itemId, "", 1, true);
        else detail(site, item);
    }

    private static boolean hasVideos(JSONArray items) {
        for (int i = 0; items != null && i < Math.min(160, items.length()); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && (!item.optString("vod_id").isEmpty() || !item.optString("vod_name").isEmpty())) return true;
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
        boolean[] all = {true};
        return new Screen(Tab.SEARCH, "搜索", true, () -> {
            LinearLayout scope = ui.horizontal();
            Button many = action(scope, "多站搜索", () -> { all[0] = true; scope.getChildAt(0).setSelected(true); scope.getChildAt(1).setSelected(false); });
            Button single = action(scope, "当前站点", () -> { all[0] = false; scope.getChildAt(0).setSelected(false); scope.getChildAt(1).setSelected(true); });
            many.setSelected(all[0]); single.setSelected(!all[0]); single.setEnabled(model != null); body.addView(scope);
            EditText input = form("输入片名、歌手或歌曲", query[0], "开始搜索", text -> {
                if (text.isEmpty()) return;
                query[0] = text;
                if (all[0] || model == null) chooseSearchSite(text);
                else listing(model.site, "搜索 · " + text, "", text, 1, true);
            });
            GridView keyboard = ui.keyboard(input);
            addGrid(body, keyboard, 10);
        });
    }

    private void chooseSearchSite(String query) {
        if (pending != null) return;
        List<BoxSource.Site> sites = new ArrayList<>();
        for (BoxSource.Site site : source.sites()) {
            if (site.searchable) sites.add(site);
        }
        startSearch(query, sites);
    }

    private void startSearch(String query, List<BoxSource.Site> sites) {
        if (disposed) return;
        if (sites.isEmpty()) { message("配置里没有可搜索站点"); return; }
        SearchRun run = new SearchRun(query, sites);
        Screen results = new Screen(Tab.SEARCH, "多站搜索 · " + query, false, () -> renderSearch(run));
        results.search = run;
        show(results, true);
        BoxSource seed = source.fork();
        searches.purge();
        try { run.fanout = new SearchFanout<>(searches, searchConcurrency, sites, site -> {
            BoxSource session = seed.fork();
            run.sessions.add(session);
            try {
                if (run.stopped) return null;
                session.select(site);
                return session.search(query, 1).optJSONArray("list");
            } finally { run.sessions.remove(session); session.close(); }
        }, new SearchFanout.Observer<BoxSource.Site, JSONArray>() {
            public void result(BoxSource.Site site, JSONArray rows, Throwable error) {
                main.post(() -> {
                    if (disposed || run.stopped) return;
                    run.completed++;
                    if (error != null) run.failures.add(site);
                    int before = run.hits.size();
                    for (int i = 0; rows != null && i < Math.min(12, rows.length()) && run.hits.size() < 160; i++) {
                        JSONObject item = rows.optJSONObject(i);
                        if (item == null || item.optString("vod_id").isEmpty()) continue;
                        if (run.ids.add(siteKey(site) + "\n" + item.optString("vod_id"))) run.hits.add(new SearchHit(site, item));
                    }
                    if (screen == results) {
                        run.adapter.notifyDataSetChanged(); searchStatus(run);
                        if (before == 0 && !run.hits.isEmpty()) focusContent();
                    }
                });
            }
            public void finished(boolean cancelled) {
                seed.close();
                main.post(() -> { run.finished = true; if (!disposed && screen == results) searchStatus(run); });
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            run.stopped = true;
            message("上次搜索正在结束，请稍后重试");
            run.stop.setEnabled(false);
            return;
        }
        main.postDelayed(() -> { if (!run.finished) { run.timedOut = true; cancelSearch(run); if (screen == results) searchStatus(run); } }, 60000);
    }

    private void renderSearch(SearchRun run) {
        run.stop = action(actions, "停止", () -> { cancelSearch(run); searchStatus(run); });
        run.retry = action(actions, "重试失败站点", () -> { if (!run.failures.isEmpty()) startSearch(run.query, new ArrayList<>(run.failures)); });
        GridView cards = ui.grid(VIDEO_COLUMNS);cards.setSelector(ui.fill(0,0));
        run.adapter = new BaseAdapter() {
            public int getCount() { return run.hits.size(); }
            public SearchHit getItem(int position) { return run.hits.get(position); }
            public long getItemId(int position) { return position; }
            public View getView(int position, View reusable, ViewGroup parent) {
                SearchHit hit = getItem(position);
                BoxTile tile = reusable instanceof BoxTile ? (BoxTile) reusable : new BoxTile(ui);
                ImageView cover = tile.bind(videoName(hit.item), hit.site.name,
                        BlUi.plain(hit.item.optString("vod_remarks")), true);
                tile.setContentDescription(hit.site.name + " · " + videoName(hit.item));
                art.load(cover, artwork(hit.site, hit.item)); return tile;
            }
        };
        cards.setAdapter(run.adapter);
        cards.setOnItemClickListener((parent, view, index, id) -> {
            screen.selection = index;
            SearchHit hit = run.hits.get(index); detail(hit.site, hit.item);
        });
        addGrid(body, cards, VIDEO_COLUMNS);
        searchStatus(run);
    }
    private void searchStatus(SearchRun run) {
        run.stop.setEnabled(!run.finished && !run.stopped);
        run.retry.setEnabled(!run.failures.isEmpty());
        message((run.timedOut ? "搜索超时，已保留结果" : run.stopped ? "已停止" : run.finished ? "搜索完成" : "搜索中") + " · " + run.completed + "/" + run.sites.size()
                + " 站 · " + run.hits.size() + " 条结果" + (run.failures.isEmpty() ? "" : " · " + run.failures.size() + " 站失败"));
    }
    private void cancelSearch(SearchRun run) {
        if (run == null || run.finished || run.stopped) return;
        run.stopped = true;
        if (run.fanout != null) run.fanout.cancel();
        synchronized (run.sessions) { for (BoxSource session : run.sessions) session.cancelRequests(); }
        searches.purge();
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
        SiteHome cached = homes.get(siteKey(site));
        load(() -> {
            source.select(site);
            SiteHome selectedSite = currentSite;
            if (selectedSite == null || selectedSite.site != site) {
                selectedSite = cached;
                if (selectedSite == null) selectedSite = new SiteHome(site, null, null, null, "");
            }
            SiteHome model = selectedSite;
            JSONObject filters = model.filters;
            JSONObject result = search.isEmpty() ? source.category(type, page, filterValues(site, type, filters))
                    : source.search(search, page);
            JSONArray items = result.optJSONArray("list");
            int pages = result.optInt("pagecount", items == null || items.length() == 0 ? page : page + 1);
            return () -> {
                currentSite = model;
                show(new Screen(tab, label, false, () -> {
                    if (search.isEmpty()) {
                        categoryHeader(model, type);
                        LinearLayout toolbar=ui.horizontal();TextView section=ui.title(label.equals("电影")?"全部电影":label);
                        toolbar.addView(section,new LinearLayout.LayoutParams(0,ui.px(54),1));body.addView(toolbar);
                        int before=body.getChildCount();filterBar(site,label,type,filters);
                        if(body.getChildCount()>before){View filtersView=body.getChildAt(before);filtersView.setVisibility(View.GONE);
                            action(toolbar,"筛选",()->filtersView.setVisibility(filtersView.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));}
                    }
                    if (page > 1) action(actions, "上一页", () -> listing(site, label, type, search, page - 1, false));
                    if (page < pages) action(actions, "下一页", () -> listing(site, label, type, search, page + 1, false));
                    message(videos(site, items) ? "第 " + page + " 页" : "没有匹配内容");
                }), push);
            };
        });
    }

    private boolean videos(BoxSource.Site site, JSONArray items) { return videos(site, items, true); }
    private boolean videos(BoxSource.Site site, JSONArray items, boolean poster) {
        List<JSONObject> videos = new ArrayList<>();
        for (int i = 0; items != null && i < Math.min(160, items.length()); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && (!item.optString("vod_id").isEmpty() || !item.optString("vod_name").isEmpty())) videos.add(item);
        }
        if (videos.isEmpty()) return false;
        if(poster&&!artwork(site,videos.get(0)).isEmpty())backdrop(artwork(site,videos.get(0)),false);
        int columns = poster ? 5 : VIDEO_COLUMNS;
        GridView cards = ui.grid(columns);cards.setSelector(ui.fill(0,0));
        cards.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return videos.size(); }
            @Override public JSONObject getItem(int position) { return videos.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View reusable, ViewGroup parent) {
                JSONObject item = getItem(position);
                BoxTile tile = reusable instanceof BoxTile ? (BoxTile) reusable : new BoxTile(ui);
                String tag = item.optString("vod_tag");
                String kind = "音乐".equals(item.optString("vod_type")) ? "音乐" : "action".equals(tag) || !item.optString("action").isEmpty() ? "操作"
                        : "folder".equals(tag) || "cate".equals(tag) ? "目录" : "影片";
                ImageView cover = tile.bind(videoName(item), metadata(item, "vod_year", "vod_area", "vod_class"),
                        BlUi.plain(item.optString("vod_remarks")), poster && !MusicSearch.supports(site.api));
                tile.setContentDescription(kind + " · " + videoName(item));
                art.load(cover, artwork(site, item));
                return tile;
            }
        });
        cards.setOnItemClickListener((parent, view, index, id) -> {
            screen.selection = index;
            JSONObject item = videos.get(index);
            openVideo(site, item);
        });
        addGrid(body, cards, columns);
        return true;
    }

    private JSONObject filterValues(BoxSource.Site site, String category, JSONObject filters) throws Exception {
        String id = site.key + "\n" + category;
        JSONObject selected = selectedFilters.get(id);
        if (selected == null) {
            selected = new JSONObject();
            JSONArray fields = filters == null ? null : filters.optJSONArray(category);
            for (int i = 0; fields != null && i < Math.min(20, fields.length()); i++) {
                JSONObject field = fields.optJSONObject(i);
                if (field == null || field.optString("key").isEmpty()) continue;
                JSONArray values = field.optJSONArray("value");
                JSONObject first = values == null ? null : values.optJSONObject(0);
                selected.put(field.optString("key"), field.has("init") && !field.isNull("init") ? field.optString("init")
                        : first == null ? "" : first.optString("v"));
            }
            selectedFilters.put(id, selected);
        }
        return new JSONObject(selected.toString());
    }

    private void filterBar(BoxSource.Site site, String label, String category, JSONObject filters) {
        JSONArray fields = filters == null ? null : filters.optJSONArray(category);
        if (fields == null || fields.length() == 0) return;
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = ui.horizontal();
        scroll.addView(row);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        JSONObject selected = selectedFilters.get(site.key + "\n" + category);
        for (int i = 0; i < Math.min(20, fields.length()); i++) {
            JSONObject field = fields.optJSONObject(i);
            if (field == null || field.optString("key").isEmpty()) continue;
            JSONArray values = field.optJSONArray("value");
            if (values == null || values.length() == 0) continue;
            String key = field.optString("key"), name = field.optString("name", key);
            String active = "";
            for (int j = 0; j < values.length(); j++) {
                JSONObject value = values.optJSONObject(j);
                if (value != null && selected != null && value.optString("v").equals(selected.optString(key))) active = value.optString("n");
            }
            action(row, name + (active.isEmpty() ? "" : " · " + active), () -> {
                List<Choice> choices = new ArrayList<>();
                for (int j = 0; j < Math.min(200, values.length()); j++) {
                    JSONObject value = values.optJSONObject(j);
                    if (value == null) continue;
                    choices.add(new Choice(value.optString("n", value.optString("v")), () -> {
                        try {
                            selected.put(key, value.optString("v"));
                            if (!history.isEmpty()) history.pop();
                            listing(site, label, category, "", 1, false);
                        } catch (org.json.JSONException error) { message("筛选条件无效"); }
                    }));
                }
                show(new Screen(Tab.CATEGORIES, "选择" + name, false, () -> choices(body, choices, 6, 48)), true);
            });
        }
    }

    private void runSiteAction(BoxSource.Site site, String command) {
        homes.remove(siteKey(site));
        authorizationFeedbackUntil = android.os.SystemClock.uptimeMillis() + 5 * 60 * 1000L;
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
            if (video == null) {
                JSONArray children = source.category(item.optString("vod_id"), 1, new JSONObject()).optJSONArray("list");
                if (hasVideos(children)) return () -> listing(site, videoName(item), item.optString("vod_id"), "", 1, true);
                throw new IOException("站点没有返回影片详情");
            }
            if (list.length() > 1 && video.optString("vod_play_url").isEmpty())
                return () -> show(new Screen(from, videoName(item), false, () -> videos(site, list)), true);
            String title = BlUi.plain(video.optString("vod_name", videoName(item)));
            String cover = artwork(site, video);
            String image = cover.isEmpty() ? artwork(site, item) : cover;
            List<PlayLine> lines = playLines(site, video);
            int[] selected = {0};
            return () -> show(new Screen(from, title, false, () -> {
                detailPage(site, video, image, lines, selected, MusicSearch.supports(site.api)
                        || site.name.contains("音乐") || video.optString("vod_type").contains("音乐"));
            }), true);
        });
    }

    private void detailPage(BoxSource.Site site, JSONObject video, String image, List<PlayLine> lines, int[] selected, boolean music) {
        if (!music) {
            if (currentSite != null) categoryHeader(currentSite, "");
            Runnable start = lines.isEmpty() ? () -> message("站点没有提供播放线路") : lines.get(selected[0]).episodes.get(0).action;
            int height = context.getResources().getDisplayMetrics().widthPixels < ui.px(960) ? 260 : 320;
            body.addView(featurePanel(site, video, "▶  开始播放", start), new LinearLayout.LayoutParams(-1, ui.px(height)));
            LinearLayout episodes = ui.vertical();
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, 0, 1);size.topMargin = ui.px(14);
            body.addView(episodes, size);
            if (lines.isEmpty()) message("站点没有提供播放线路"); else episodePanel(episodes, lines, selected, false);
            return;
        }
        LinearLayout about = ui.horizontal(), info = ui.vertical(), playback = ui.vertical();
        ImageView cover = ui.imageView();
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.setBackgroundDrawable(ui.glass(0x55304452, 16));
        cover.setVisibility(image.isEmpty() ? View.GONE : View.VISIBLE);
        art.load(cover, image);
        about.addView(cover, new LinearLayout.LayoutParams(ui.px(music ? 96 : 64), ui.px(96)));
        about.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        String artist = music && !lines.isEmpty() ? sharedArtist(lines.get(selected[0]).episodes) : "";
        if (!artist.isEmpty()) info.addView(detailText(artist, 22, BlUi.TEXT, 2));
        String facts = metadata(video, "vod_year", "vod_area", "vod_class", "vod_remarks");
        if (!facts.isEmpty()) info.addView(detailText(facts, 17, BlUi.TEXT, 2));
        String cast = metadata(video, "vod_director", "vod_actor");
        if (!cast.isEmpty()) info.addView(detailText(cast, 16, BlUi.SUB, 2));
        String synopsis = BlUi.plain(video.optString("vod_content")).trim();
        TextView description = detailText(synopsis, 16, BlUi.SUB, 6);
        if (!synopsis.isEmpty()) info.addView(description);
        LinearLayout details = new LinearLayout(context) {
            private int mode;
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                boolean wide = !image.isEmpty() && View.MeasureSpec.getSize(widthSpec) >= ui.px(840);
                if (mode == (wide ? 1 : 2)) { super.onMeasure(widthSpec, heightSpec); return; }
                mode = wide ? 1 : 2;
                setOrientation(wide ? HORIZONTAL : VERTICAL);
                about.setOrientation(wide ? VERTICAL : HORIZONTAL);
                about.setGravity(Gravity.TOP);
                about.setLayoutParams(new LinearLayout.LayoutParams(wide ? ui.px(220) : -1, wide ? -1 : -2));
                cover.setLayoutParams(new LinearLayout.LayoutParams(ui.px(wide ? 220 : music ? 96 : 64),
                        ui.px(wide ? music ? 220 : 300 : 96)));
                LinearLayout.LayoutParams text = new LinearLayout.LayoutParams(wide ? -1 : 0, -2, wide ? 0 : 1);
                text.topMargin = wide ? ui.px(16) : 0;
                text.leftMargin = !wide && !image.isEmpty() ? ui.px(16) : 0;
                info.setLayoutParams(text);
                description.setMaxLines(wide ? 6 : 2);
                LinearLayout.LayoutParams list = new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : 0, 1);
                list.leftMargin = wide ? ui.px(24) : 0;
                list.topMargin = wide ? 0 : ui.px(12);
                playback.setLayoutParams(list);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        details.setOrientation(LinearLayout.VERTICAL);
        details.setBaselineAligned(false);
        details.setGravity(Gravity.TOP);
        details.setPadding(0, ui.px(12), 0, 0);
        details.addView(about, new LinearLayout.LayoutParams(-1, -2));
        details.addView(playback, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(details, new LinearLayout.LayoutParams(-1, 0, 1));
        if (lines.isEmpty()) message("站点没有提供播放线路");
        else episodePanel(playback, lines, selected, music);
    }

    private static String sharedArtist(List<Choice> items) {
        if (items.size() < 3) return "";
        String first = items.get(0).title;
        int separator = first.indexOf(" - ");
        if (separator < 1 || separator > 40) return "";
        String prefix = first.substring(0, separator + 3);
        for (Choice item : items) if (!item.title.startsWith(prefix)) return "";
        return first.substring(0, separator).trim();
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
                line.episodes.add(new Choice(name, () -> play(site, flag, id, videoName(video) + " · " + name)));
                if (++episodeCount >= 500) break;
            }
            if (!line.episodes.isEmpty()) lines.add(line);
        }
        return lines;
    }

    private void episodePanel(LinearLayout parent, List<PlayLine> lines, int[] selected, boolean music) {
        LinearLayout header = ui.horizontal(), channels = ui.horizontal(), panel = ui.vertical();
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false); scroll.addView(channels);
        TextView section=ui.title(music?"歌曲":"选集");
        section.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,ui.px(26));
        header.addView(section,new LinearLayout.LayoutParams(-2,-2));
        TextView count = ui.text("", 16, BlUi.SUB);
        count.setPadding(ui.px(12), 0, ui.px(4), 0);
        header.addView(count, new LinearLayout.LayoutParams(-2, -2));
        scroll.setFillViewport(true);channels.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        header.addView(scroll,new LinearLayout.LayoutParams(0,-2,1));
        parent.addView(header, new LinearLayout.LayoutParams(-1, ui.px(44)));
        TextView fullTitle = ui.text("", 17, BlUi.SUB);
        fullTitle.setSingleLine(true); fullTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        fullTitle.setMarqueeRepeatLimit(-1); fullTitle.setSelected(true);
        parent.addView(fullTitle, new LinearLayout.LayoutParams(-1, ui.px(30)));
        parent.addView(panel, new LinearLayout.LayoutParams(-1, 0, 1));
        Runnable bind = () -> {
            List<Choice> episodes = lines.get(selected[0]).episodes;
            count.setText((music?"":"共 ")+episodes.size() + (music ? " 首" : " 集"));
            panel.removeAllViews();
            episodeChoices(panel, episodes, music, fullTitle);
        };
        for (int i = 0; i < lines.size(); i++) {
            int index = i;
            if (lines.size() == 1) {
                TextView name = ui.text(lines.get(i).name, 19, BlUi.TEXT);
                channels.addView(name, new LinearLayout.LayoutParams(-2, ui.px(44)));
                continue;
            }
            Button option = action(channels, lines.get(i).name, () -> {
                if (pending != null) return;
                selected[0] = index; screen.selection = 0;
                for (int j = 0; j < channels.getChildCount(); j++) channels.getChildAt(j).setSelected(j == index);
                bind.run(); focusContent();
            });
            option.setSelected(i == selected[0]);
        }
        bind.run();
    }

    private void episodeChoices(LinearLayout parent, List<Choice> items, boolean music, TextView fullTitle) {
        boolean longNames = music;
        for (Choice item : items) if (item.title.codePointCount(0, item.title.length()) > 14) longNames = true;
        final int columns = longNames ? 3 : 6, minWidth = longNames ? 230 : 110;
        String artist = music ? sharedArtist(items) : "";
        String prefix = artist.isEmpty() ? "" : artist + " - ";
        GridView options = new GridView(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int count = Math.max(1, Math.min(columns, View.MeasureSpec.getSize(widthSpec) / ui.px(minWidth)));
                if (View.MeasureSpec.getSize(widthSpec) > 0 && getNumColumns() != count) setNumColumns(count);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        options.setNumColumns(columns); options.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        options.setHorizontalSpacing(ui.px(10)); options.setVerticalSpacing(ui.px(10));
        options.setPadding(ui.px(2), ui.px(8), ui.px(2), ui.px(12)); options.setClipToPadding(false);
        options.setSelector(ui.selector()); options.setDrawSelectorOnTop(true);
        options.setAdapter(new BaseAdapter() {
            public int getCount() { return items.size(); }
            public Choice getItem(int position) { return items.get(position); }
            public long getItemId(int position) { return position; }
            public View getView(int position, View reuse, ViewGroup container) {
                LinearLayout item = reuse instanceof LinearLayout ? (LinearLayout) reuse : ui.horizontal();
                if (reuse == null) {
                    item.setPadding(ui.px(12), ui.px(9), ui.px(12), ui.px(9));
                    item.setMinimumHeight(ui.px(columns == 3 ? 68 : 60));
                    item.setBackgroundDrawable(ui.episodeBackground());
                    TextView number = ui.text("", 15, BoxUi.SUB);
                    number.setTextColor(ui.episodeText());number.setDuplicateParentStateEnabled(true);
                    number.setVisibility(columns==6?View.GONE:View.VISIBLE);
                    item.addView(number, new LinearLayout.LayoutParams(ui.px(30), -1));
                    TextView name = ui.text("", 22, BlUi.TEXT);
                    name.setTextColor(ui.episodeText());
                    name.setDuplicateParentStateEnabled(true);
                    name.setGravity((columns==6?Gravity.CENTER:Gravity.LEFT)|Gravity.CENTER_VERTICAL);
                    name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
                    item.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                }
                Choice choice = getItem(position);
                ((TextView) item.getChildAt(0)).setText(String.format(java.util.Locale.US, "%02d", position + 1));
                ((TextView) item.getChildAt(1)).setText(prefix.isEmpty() ? choice.title : choice.title.substring(prefix.length()));
                item.setContentDescription((position + 1) + " · " + choice.title);
                return item;
            }
        });
        options.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> owner, View view, int position, long id) {
                fullTitle.setText((position + 1) + " · " + items.get(position).title);
            }
            public void onNothingSelected(AdapterView<?> owner) { }
        });
        options.setOnItemClickListener((owner, view, position, id) -> {
            screen.selection = position; items.get(position).action.run();
        });
        fullTitle.setText(items.get(Math.min(screen.selection, items.size() - 1)).title);
        addGrid(parent, options, columns);
    }

    private void play(BoxSource.Site site, String flag, String id, String title) {
        authorizationFeedbackUntil = 0;
        load(() -> {
            source.select(site);
            JSONObject result = source.play(flag, id);
            JSONObject headers = result.optJSONObject("header");
            if (headers == null && result.opt("header") instanceof String && !result.optString("header").trim().isEmpty()) {
                headers = new JSONObject(result.optString("header"));
            }
            JSONObject mediaHeaders = headers;
            String url = result.optString("url");
            okhttp3.HttpUrl address = BoxHttp.url(url);
            boolean dash = result.optString("format").toLowerCase(java.util.Locale.US).contains("dash")
                    || address.encodedPath().endsWith(".mpd") || "mpd".equals(address.queryParameter("type"));
            if (dash && android.os.Build.VERSION.SDK_INT < 16) throw new IOException("DASH 播放需要 Android 4.1 或更新系统");
            if (dash && !(host instanceof ChannelPlugin.PlayerUiHost)) throw new IOException("请更新 nTv 以使用统一播放控件");
            return () -> {
                if (dash) {
                    try {
                        dashPlayer = new BoxDashPlayer(ui, (ChannelPlugin.PlayerUiHost) host, http, source, url, mediaHeaders, title, this::closeDashPlayer);
                        root.setVisibility(View.GONE);
                        container.addView(dashPlayer, new FrameLayout.LayoutParams(-1, -1));
                    } catch (RuntimeException | LinkageError error) {
                        closeDashPlayer();
                        message("DASH 播放初始化失败 · " + error.getClass().getSimpleName());
                    }
                } else {
                    try { host.playVod(url, mediaHeaders, title); }
                    catch (LinkageError olderHost) { host.playVod(url, mediaHeaders); }
                }
            };
        });
    }

    private void closeDashPlayer() {
        if (dashPlayer == null) return;
        dashPlayer.close();
        container.removeView(dashPlayer);
        dashPlayer = null;
        root.setVisibility(View.VISIBLE);
        focusContent();
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
                    item.setPadding(ui.px(16), ui.px(12), ui.px(16), ui.px(12));
                    item.setMinHeight(ui.px(height >= 58 ? 94 : height));
                    if (height >= 58) {
                        Drawable icon = context.getResources().getDrawable(android.R.drawable.ic_menu_slideshow).mutate();
                        icon.setColorFilter(0xff8fdbff, PorterDuff.Mode.SRC_IN);
                        icon.setBounds(0, 0, ui.px(30), ui.px(30));
                        item.setCompoundDrawables(null, icon, null, null);
                        item.setCompoundDrawablePadding(ui.px(10));
                    }
                    item.setMaxLines(2);
                    item.setEllipsize(TextUtils.TruncateAt.END);
                    item.setBackgroundDrawable(ui.glass(0x55415967, 16));
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
            if (code != KeyEvent.KEYCODE_DPAD_LEFT || Math.max(0, content.getSelectedItemPosition()) % Math.max(1, content.getNumColumns()) != 0) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) navigation.get(screen.tab).requestFocus();
            return true;
        });
        parent.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private Button action(LinearLayout parent, String label, Runnable run) {
        Button button = ui.button(label);
        int icon=label.equals("返回")?5:label.equals("↻")?6:label.startsWith("▶")?7:label.equals("搜索同名")?3:label.equals("筛选")?8:-1;
        if(icon>=0){
            button.setText(label.startsWith("▶")?label.substring(1).trim():label.equals("↻")?"":label);
            Drawable glyph=ui.actionIcon(icon);glyph.setBounds(0,0,ui.px(24),ui.px(24));
            button.setCompoundDrawables(glyph,null,null,null);button.setCompoundDrawablePadding(ui.px(label.equals("↻")?0:8));
            button.setContentDescription(label.equals("↻")?"刷新站点":button.getText());
        }
        button.setAllCaps(false);
        button.setOnClickListener(view -> run.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
        size.rightMargin = ui.px(8);
        parent.addView(button, size);
        return button;
    }

    private void show(Screen next, boolean push) {
        if (screen != null && screen != next) cancelSearch(screen.search);
        if (push && screen != null) {
            saveSelection();
            history.push(screen);
            if (history.size() > MAX_HISTORY) history.removeLast();
        }
        screen = next;
        ambientBackdrop();
        if (next.tab == Tab.SITES) stations = next;
        heading.setText(next.title);
        heading.setVisibility(View.VISIBLE);
        categoryTabs.setVisibility(View.GONE);
        categoryButtons.removeAllViews();
        message("");
        actions.removeAllViews();
        body.removeAllViews();
        grid = null;
        primary = null;
        boolean browsing = next.tab != Tab.CONFIG && next.tab != Tab.SITES;
        for (Map.Entry<Tab, Button> entry : navigation.entrySet()) {
            Tab tab = entry.getKey();
            entry.getValue().setVisibility(browsing || tab == Tab.SITES || tab == Tab.CONFIG || tab == Tab.SEARCH ? View.VISIBLE : View.GONE);
            entry.getValue().setSelected(tab == next.tab);
        }
        if (!history.isEmpty() && !(next.tab==Tab.HOME&&next.landing)) action(actions, "返回", this::onBack);
        next.render.run();
        focusContent();
    }

    private void ambientBackdrop() {
        backdropArt.setVisibility(View.GONE);
        backdropShade.setBackgroundDrawable(BoxBackdrop.scrim(false));
        backdropShade.setVisibility(View.VISIBLE);
    }

    private void backdrop(String url,boolean cinema) {
        if(cinema){
            backdropArt.setVisibility(View.VISIBLE);
            backdropShade.setBackgroundDrawable(BoxBackdrop.scrim(true));
            backdropShade.setVisibility(View.VISIBLE);
        }else ambientBackdrop();
        if(url.equals(backdropUrl)&&backdropArt.getDrawable()!=null)return;
        backdropUrl=url;int request=++backdropGeneration;
        int edge=Math.min(1440,Math.round(Math.max(context.getResources().getDisplayMetrics().widthPixels,
                context.getResources().getDisplayMetrics().heightPixels)*1.25f));
        art.load(backdropArt,url,edge,bitmap -> art.frost(bitmap,blurred -> {
            if(disposed||request!=backdropGeneration)return;
            ambientArt.setTag(url);ambientArt.setImageBitmap(blurred);sidebarGlass.image(blurred);
        }));
    }

    private void saveSelection() {
        if (grid != null && grid.getSelectedItemPosition() >= 0) {
            screen.selection = grid.getSelectedItemPosition();
        }
    }

    private void focusContent() {
        if (primary != null && primary != grid) {
            primary.requestFocusFromTouch();
        } else if (grid != null && grid.getCount() > 0) {
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
        Set<String> values = new LinkedHashSet<>();
        for (String key : keys) {
            String value = BlUi.plain(item.optString(key)).trim();
            if (!value.isEmpty()&&!value.equals("0")&&!value.equalsIgnoreCase("null")&&!value.equalsIgnoreCase("undefined")) values.add(value);
        }
        return TextUtils.join("  ·  ", values);
    }

    private String artwork(BoxSource.Site site, JSONObject item) {
        String picture = item.optString("vod_pic").trim();
        if (picture.isEmpty()) return "";
        try {
            int offset = BoxArt.headerOffset(picture);
            String suffix = offset < 0 ? "" : picture.substring(offset);
            if (offset >= 0) picture = picture.substring(0, offset);
            picture = source.playbackUrl(picture);
            String origin = site.type == 3 ? site.origin : BoxHttp.resolve(site.origin, site.api);
            return BoxHttp.resolve(origin, picture) + suffix;
        } catch (Exception invalid) {
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
        if (dashPlayer != null) { if (!dashPlayer.hideControls()) closeDashPlayer(); return true; }
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

    @Override public void setActive(boolean active) { if (dashPlayer != null) dashPlayer.setActive(active); }
    @Override public void update(long positionMs, boolean playing) { }

    @Override public void dispose() {
        if (disposed) return;
        disposed = true;
        if (screen != null) cancelSearch(screen.search);
        searches.shutdownNow();
        if (authorizationPreferences != null && authorizationListener != null)
            authorizationPreferences.unregisterOnSharedPreferenceChangeListener(authorizationListener);
        closeDashPlayer();
        if (sniffer != null) sniffer.close();
        if (pending != null) pending.cancel(true);
        if (art != null) art.close();
        if (ui != null) ui.close();
        main.removeCallbacksAndMessages(null);
        worker.execute(() -> { if (source != null) source.close(); });
        worker.shutdown();
    }
}
