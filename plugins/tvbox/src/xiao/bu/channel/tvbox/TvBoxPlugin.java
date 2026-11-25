package xiao.bu.channel.tvbox;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
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
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
    private static final int HEADER_ITEM_HEIGHT = 48;

    private enum Tab { SITES, HOME, CATEGORIES, SEARCH }

    private interface Load { Runnable run() throws Exception; }
    private interface Submit { void accept(String text); }

    private static final class Screen {
        final Tab tab;
        final String title;
        final boolean landing;
        final Runnable render;
        int selection;
        SearchRun search;
        List<Choice> stationChoices = Collections.emptyList();

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

    private PlayLine playbackLine;
    private int playbackEpisode;
    private Dialog episodeDialog;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor homeLoads = new ThreadPoolExecutor(2, 2, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(1));
    private final Handler homeResults = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor recommendations=new ThreadPoolExecutor(2,2,10,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(2));
    private final List<Future<?>> recommendationJobs=new ArrayList<>();
    private final Set<BoxSource> recommendationSessions=Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<BoxSource,Boolean>());
    private Runnable previewRequest;
    private Future<?> previewJob;
    private BoxSource previewSession;
    private final BoxDataCache pages=new BoxDataCache();
    private int recommendationEpoch;
    private final int searchConcurrency = android.os.Build.VERSION.SDK_INT < 21 ? 2 : 3;
    private final ThreadPoolExecutor searches = new ThreadPoolExecutor(searchConcurrency, searchConcurrency,
            20, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(6));
    private final Map<String, SiteHome> homes = new LinkedHashMap<String, SiteHome>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, SiteHome> entry) { return size() > 8; }
    };
    private final ArrayDeque<Screen> history = new ArrayDeque<>();
    private final Map<String, JSONObject> selectedFilters = new HashMap<>();
    private BoxUi ui;
    private BoxArt art;
    private Context context;
    private VodHost host;
    private BoxSource source;
    private BoxHttp http;
    private BoxHttp browseHttp;
    private FrameLayout container;
    private ImageView backdropArt;
    private ImageView ambientArt;
    private View backdropShade;
    private BoxGlass controlGlass;
    private String backdropKey="";
    private int backdropGeneration;
    private BoxDashPlayer dashPlayer;
    private BoxSniffer sniffer;
    private LinearLayout root, body, actions;
    private TextView heading, status;
    private HorizontalScrollView categoryTabs;
    private LinearLayout categoryButtons;
    private GridView grid;
    private BoxHomePage homePage;
    private Dialog homeMenu;
    private Button homeSite, homeSearch, homeFavorites;
    private final Map<String, BoxSource.Site> knownSites = new HashMap<>();
    private BoxFavorites favorites;
    private BoxSource.Site featuredSite;
    private JSONObject featuredItem;
    private View primary;
    private Screen screen, stations;
    private SiteHome currentSite;
    private String configUrl;
    private Future<?> pending;
    private BoxSource pendingHome;
    private Dialog loadingDialog;
    private String statusBeforeInlineLoading;
    private volatile boolean disposed;
    private int generation;
    private android.content.SharedPreferences authorizationPreferences;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener authorizationListener;
    private long authorizationFeedbackUntil;
    private CloudLoginAction cloudLoginAction;

    private static final class CloudLoginAction {
        final BoxSource.Site site;
        final String command;
        final Screen screen;

        CloudLoginAction(BoxSource.Site site, String command, Screen screen) {
            this.site = site;
            this.command = command;
            this.screen = screen;
        }
    }

    @Override public View create(Context context, ClassLoader assets, JSONObject config, Host base) throws Exception {
        if (!(base instanceof VodHost)) throw new IOException("请更新 nTv 以运行 TVBox");
        host = (VodHost) base;
        this.context = context;
        ui = new BoxUi(context,assets,(OkHttpClient)host.httpClient());
        http = new BoxHttp((OkHttpClient) host.httpClient());
        browseHttp = http.isolated();
        source = new BoxSource(context, host, assets, browseHttp);
        favorites = new BoxFavorites(context);
        homeLoads.allowCoreThreadTimeOut(true);
        int screenWidth = Math.max(context.getResources().getDisplayMetrics().widthPixels,
                context.getResources().getDisplayMetrics().heightPixels);
        int thumbnailEdge = android.os.Build.VERSION.SDK_INT <= 19
                ? Math.min(640, Math.max(320, screenWidth / 3)) : 640;
        art = new BoxArt(http, thumbnailEdge);
        container = new FrameLayout(context) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                boolean hadFocus=hasFocus();
                if(super.dispatchKeyEvent(event))return true;
                if(!hadFocus){
                    if(dashPlayer!=null&&dashPlayer.isShown())return dashPlayer.dispatchKeyEvent(event);
                    if(root!=null&&root.isShown())return root.dispatchKeyEvent(event);
                }
                return false;
            }
        };container.setBackgroundColor(0xff000000);
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
                int direction = focusDirection(event.getKeyCode());
                if (homePage != null && event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0 && event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
                    homePage.restartRotation();
                }
                if(event.getAction()==KeyEvent.ACTION_DOWN&&findFocus()==null
                        &&(direction!=0||event.getKeyCode()==KeyEvent.KEYCODE_DPAD_CENTER
                            ||event.getKeyCode()==KeyEvent.KEYCODE_ENTER)){
                    focusContent();
                    boolean carouselMove = homePage != null && homePage.isFocused()
                            && (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT);
                    if (!carouselMove && direction != 0 && findFocus() != null) return true;
                }
                if (homePage != null && homePage.handleKey(event)) return true;
                if (homePage != null && homePage.isFocused() && direction == View.FOCUS_UP
                        && event.getAction() == KeyEvent.ACTION_DOWN && categoryButtons.getChildCount() > 0
                        && categoryButtons.getChildAt(0).requestFocus()) return true;
                if (super.dispatchKeyEvent(event)) return true;
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (direction == 0) return false;
                View next = FocusFinder.getInstance().findNextFocus(this, findFocus(), direction);
                return next != null && next.requestFocus(direction);
            }
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                int action = event.getActionMasked();
                if (homePage != null && (action == android.view.MotionEvent.ACTION_DOWN
                        || action == android.view.MotionEvent.ACTION_UP)) {
                    homePage.restartRotation();
                }
                return super.dispatchTouchEvent(event);
            }
        };
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBaselineAligned(false);
        root.setBackgroundColor(0x00000000);
        root.setPadding(ui.px(24), ui.px(32), ui.px(32), ui.px(24));
        LinearLayout page = ui.vertical();
        controlGlass = new BoxGlass(page, container, ui.px(28), Math.max(1, ui.px(1)));
        ui.material(controlGlass);
        root.addView(page, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout header = ui.horizontal();
        header.setBaselineAligned(false);
        homeSite = ui.homeButton("站点  ⌄", true);
        homeSite.setContentDescription("切换站点");
        homeSite.setMaxWidth(ui.px(215));
        homeSite.setOnClickListener(view -> openHomeStations());
        LinearLayout.LayoutParams stationSize = new LinearLayout.LayoutParams(-2, ui.px(HEADER_ITEM_HEIGHT));
        stationSize.rightMargin = ui.px(20);
        header.addView(homeSite, stationSize);
        heading = ui.title("");
        heading.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.px(30));
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(heading, new LinearLayout.LayoutParams(0, ui.px(56), 1));
        categoryTabs = new HorizontalScrollView(context);
        categoryTabs.setHorizontalScrollBarEnabled(false);
        categoryTabs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        categoryButtons = ui.horizontal();
        categoryButtons.setBaselineAligned(false);
        categoryTabs.addView(categoryButtons);
        categoryTabs.setVisibility(View.GONE);
        LinearLayout.LayoutParams tabsSize = new LinearLayout.LayoutParams(0, ui.px(HEADER_ITEM_HEIGHT), 1);
        tabsSize.rightMargin = ui.px(12);
        header.addView(categoryTabs, tabsSize);
        actions = ui.horizontal();
        header.addView(actions, new LinearLayout.LayoutParams(-2, -2));
        homeSearch = homeHeaderAction(header, "搜索", 3, () -> switchTab(Tab.SEARCH));
        homeFavorites = homeHeaderAction(header, "收藏", 9, this::openFavorites);
        linkHomeHeader(homeSite);
        linkHomeHeader(homeSearch);
        linkHomeHeader(homeFavorites);
        TextView clock = ui.text("", 24, BoxUi.TEXT);
        clock.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        clock.setPadding(ui.px(12), 0, 0, 0);
        header.addView(clock, new LinearLayout.LayoutParams(-2, ui.px(HEADER_ITEM_HEIGHT)));
        Runnable updateClock = new Runnable() {
            public void run() {
                if (disposed) return;
                clock.setText(android.text.format.DateFormat.format("HH:mm", new java.util.Date()));
                main.postDelayed(this, 30000);
            }
        };
        updateClock.run();
        page.addView(header,new LinearLayout.LayoutParams(-1,ui.px(64)));
        status = ui.text("", 16, BlUi.SUB);
        status.setMaxLines(2);
        status.setPadding(0, ui.px(6), 0, ui.px(6));
        page.addView(status);
        body = ui.vertical();
        body.setPadding(0,ui.px(16),ui.px(4),0);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        configUrl = config.optString("url").trim();
        show(unconfiguredScreen(), false);
        if (!configUrl.isEmpty()) openRepository(configUrl, true);
        container.addView(root, new FrameLayout.LayoutParams(-1, -1));
        sniffer = new BoxSniffer(context, container, http);
        source.setSniffer(sniffer);
        authorizationPreferences = context.getSharedPreferences("spUtils", Context.MODE_PRIVATE);
        authorizationListener = (preferences, key) -> {
            if (key == null || !key.matches("(?i)(?:quark|uc|baidu|bd|ali|aliyun).*?(?:cookie|token|_ck|_st)$")) return;
            boolean quark = key.regionMatches(true, 0, "quark", 0, 5);
            main.post(() -> {
                if (disposed) return;
                homes.clear();pages.clear();cancelRecommendations();
                if (pending != null) cancelPending();
                boolean feedback = android.os.SystemClock.uptimeMillis() <= authorizationFeedbackUntil;
                authorizationFeedbackUntil = 0;
                if (!feedback) return;
                CloudLoginAction login = cloudLoginAction;
                if (quark && login != null && login.screen == screen && storedAuthorization(preferences, key)) {
                    BoxCloudLoginPanel.refresh(root, main, () -> runSiteAction(login.site, login.command, false),
                            () -> !disposed && root.isShown() && screen == login.screen
                                    && cloudLoginAction == login && pending == null);
                }
                String text = quark ? "夸克授权信息已更新" : "网盘授权信息已更新";
                message(text);
                android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show();
            });
        };
        authorizationPreferences.registerOnSharedPreferenceChangeListener(authorizationListener);
        return container;
    }

    private static boolean storedAuthorization(android.content.SharedPreferences preferences, String key) {
        try { return !preferences.getString(key, "").trim().isEmpty(); }
        catch (ClassCastException unexpectedType) { return false; }
    }

    private void switchTab(Tab tab) {
        if (pending != null || screen.tab == tab && screen.landing) return;
        if (currentSite == null && tab != Tab.SITES && tab != Tab.SEARCH) return;
        switch (tab) {
            case SITES:
                if (stations == null) show(unconfiguredScreen(), false);
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

    private Screen unconfiguredScreen() {
        return new Screen(Tab.SITES, "站点", true, () -> {
            if (!configUrl.isEmpty()) {
                primary = action(body, "重新加载", () -> openRepository(configUrl, true));
                return;
            }
            TextView hint=ui.text("请在插件地址的 url 参数中指定配置源",24,BoxUi.SUB);
            hint.setPadding(0,ui.px(24),0,ui.px(16));body.addView(hint);
            body.addView(ui.text("tvbox.jar?url=配置地址",22,BoxUi.TEXT));
        });
    }

    private void openRepository(String url, boolean topLevel) {
        load("正在加载配置…", () -> {
            source.load(url);
            List<BoxSource.Site> sites = source.sites();
            List<BoxSource.Repository> repos = source.repositories();
            return () -> {
                if (topLevel) {
                    history.clear();
                    configUrl = url;
                }
                homes.clear();pages.clear();cancelRecommendations();
                currentSite = null;
                List<Choice> choices = new ArrayList<>();
                for (BoxSource.Repository repo : repos)
                    choices.add(new Choice(repo.name, () -> openRepository(repo.url, false)));
                for (BoxSource.Site site : sites) {
                    knownSites.put(siteKey(site), site);
                    choices.add(new Choice(site.name, () -> openSite(site)));
                }
                Screen next = new Screen(Tab.SITES, "站点", true, () -> {
                    choices(body, choices, 4, 64);
                    if (choices.isEmpty()) message("配置里没有可用站点");
                });
                next.stationChoices = choices;
                show(next, !topLevel);
            };
        });
    }

    private void openSite(BoxSource.Site site) {
        if (pending != null) return;
        cancelRecommendations();
        if (site.ext.isEmpty() && site.name.contains("请勿") && site.name.contains("广告")) {
            SiteHome notice = new SiteHome(site, null, null, null, site.name);
            currentSite = notice;
            show(new Screen(Tab.HOME, site.name, true, () -> {
                categoryHeader(notice, "");
                message(site.name);
            }), true);
            return;
        }
        SiteHome cached = homes.get(siteKey(site));
        if (cached != null && android.os.SystemClock.elapsedRealtime() - cached.created < 120000) {
            currentSite=cached;show(homeScreen(cached),true);if(!hasVideos(cached.videos)&&cached.notice.isEmpty())startRecommendations(cached);
            return;
        }
        BoxSource session = source.homeSession();
        pendingHome = session;
        int request = ++generation;
        try {
            pending = homeLoads.submit(() -> {
                JSONObject data;
                String notice = "";
                try {
                    session.select(site);
                    data = session.home();
                } catch (Exception | LinkageError error) {
                    String compatibility = unsupportedAndroid(error);
                    if (compatibility != null) {
                        session.close();
                        homeResults.post(() -> {
                            if (disposed || generation != request) return;
                            pendingHome = null;
                            finishLoading();
                            compatibilityToast(compatibility);
                        });
                        return;
                    }
                    data = new JSONObject();
                    notice = "首页加载失败，可尝试搜索";
                }
                if (Thread.currentThread().isInterrupted() || disposed) {
                    session.close();
                    return;
                }
                SiteHome model = new SiteHome(site, data.optJSONArray("class"), data.optJSONArray("list"),
                        data.optJSONObject("filters"), notice);
                homeResults.post(() -> finishHome(request, session, model));
            });
            showLoading("正在加载站点…");
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            pendingHome = null;
            session.close();
            message("站点请求仍在结束，请稍后从站点列表重试，或使用搜索");
        }
    }

    private void finishHome(int request, BoxSource session, SiteHome model) {
        if (disposed) {
            new Thread(session::close, "tvbox-home-cleanup").start();
            return;
        }
        if (generation != request) {
            worker.execute(session::close);
            return;
        }
        pendingHome = null;
        finishLoading();
        worker.execute(() -> {
            try {
                source.adoptHome(session);
            } finally {
                session.close();
            }
        });
        currentSite = model;
        if (model.notice.isEmpty() || model.categories != null || hasVideos(model.videos)) {
            homes.put(siteKey(model.site), model);
        }
        show(homeScreen(model), true);
        if (!hasVideos(model.videos) && model.notice.isEmpty()) startRecommendations(model);
    }
    private static String siteKey(BoxSource.Site site) { return site.origin + "\n" + site.key + "\n" + site.api; }

    private void cancelRecommendations(){
        recommendationEpoch++;for(Future<?> job:recommendationJobs)job.cancel(true);recommendationJobs.clear();
        for(BoxSource session:recommendationSessions)session.cancelRequests();recommendationSessions.clear();recommendations.purge();
    }
    private void startRecommendations(SiteHome base){
        final int epoch=recommendationEpoch;Screen owner=screen;
        int tasks=(base.site.java()||!base.site.executable())&&base.categories!=null&&base.categories.length()>0?2:1;int[] remaining={tasks};
        message("正在获取推荐，可先浏览分类或搜索");
        for(int kind=0;kind<tasks;kind++){
            int operation=kind;BoxSource session=source.fork();recommendationSessions.add(session);
            try{recommendationJobs.add(recommendations.submit(()->{
                JSONArray result=null;String notice="",cacheKey="";JSONObject cachedPage=null;
                try{
                    session.select(base.site);boolean[] prepared={base.site.javascript()||base.site.python()};
                    if(prepared[0])result=session.home().optJSONArray("list");
                    if(!hasVideos(result)&&operation==0)result=probeHome(session,false,"",new JSONObject(),prepared).optJSONArray("list");
                    if(!hasVideos(result)&&(operation==1||tasks==1&&base.categories!=null))for(int i=0;i<Math.min(2,base.categories.length());i++){
                        if(Thread.currentThread().isInterrupted())break;JSONObject category=base.categories.optJSONObject(i);if(category==null||category.optString("type_id").isEmpty())continue;
                        String id=category.optString("type_id");JSONObject values=initialFilters(id,base.filters);JSONObject data=probeHome(session,true,id,values,prepared);result=data.optJSONArray("list");
                        if(hasVideos(result)){cachedPage=data;cacheKey=siteKey(base.site)+"\n"+id+"\n1\n"+values;notice="分类 · "+category.optString("type_name",id);break;}
                    }
                }catch(Exception|LinkageError ignored){}
                finally{recommendationSessions.remove(session);session.close();}
                JSONArray items=result;String info=notice,key=cacheKey;JSONObject page=cachedPage;
                main.post(()->{
                    if(disposed||epoch!=recommendationEpoch||screen!=owner)return;
                    if(hasVideos(items)){if(page!=null)pages.put(key,page);SiteHome complete=new SiteHome(base.site,base.categories,items,base.filters,info);homes.put(siteKey(base.site),complete);currentSite=complete;cancelRecommendations();show(homeScreen(complete),false);}
                    else if(--remaining[0]==0)message("站点未返回推荐，可选择分类或搜索");
                });
            }));}catch(java.util.concurrent.RejectedExecutionException busy){recommendationSessions.remove(session);session.close();if(--remaining[0]==0)message("可先选择分类或搜索");}
        }
    }
    private static JSONObject initialFilters(String category,JSONObject definitions)throws Exception{
        JSONObject values=new JSONObject();JSONArray fields=definitions==null?null:definitions.optJSONArray(category);
        for(int i=0;fields!=null&&i<Math.min(20,fields.length());i++){JSONObject field=fields.optJSONObject(i);if(field==null||field.optString("key").isEmpty())continue;JSONArray options=field.optJSONArray("value");JSONObject first=options==null?null:options.optJSONObject(0);values.put(field.optString("key"),field.has("init")?field.optString("init"):first==null?"":first.optString("v"));}
        return values;
    }

    private static JSONObject probeHome(BoxSource session,boolean category,String id,JSONObject filters,boolean[] prepared)throws Exception{
        try{JSONObject data=category?session.category(id,1,filters):session.recommendations();if(prepared[0]||hasVideos(data.optJSONArray("list")))return data;}
        catch(Exception|LinkageError failure){
            if(failure instanceof InterruptedException||failure instanceof java.io.InterruptedIOException||Thread.currentThread().isInterrupted()){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("推荐加载已取消");}
            boolean initialization=failure instanceof NullPointerException||failure instanceof IllegalStateException||"站点接口返回空内容".equals(failure.getMessage());
            if(prepared[0]||!initialization)throw failure;
        }
        if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("推荐加载已取消");
        JSONObject home=session.home();prepared[0]=true;
        if(!category&&hasVideos(home.optJSONArray("list")))return home;
        return category?session.category(id,1,filters):session.recommendations();
    }
    private Screen homeScreen(SiteHome model) {
        return new Screen(Tab.HOME, model.site.name, true, () -> {
            currentSite = model;
            homeSite.setText(shortSiteName(model.site.name) + "  ⌄");
            categoryHeader(model, "");
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
                    categoryScreen(model).render.run();
                } else {
                    primary = action(body, "搜索影片", () -> switchTab(Tab.SEARCH));
                }
            }
        });
    }

    private boolean homeVideos(SiteHome model) {
        List<JSONObject> videos = new ArrayList<>();
        List<BoxHomePage.Item> items = new ArrayList<>();
        boolean hasFeature = false;
        for (int i = 0; model.videos != null && i < Math.min(160, model.videos.length()); i++) {
            JSONObject item = model.videos.optJSONObject(i);
            if (item == null || videoName(item).isEmpty()) continue;
            videos.add(item);
            hasFeature |= isMediaEntry(item);
            items.add(new BoxHomePage.Item(videoName(item),
                    metadata(item, "vod_year", "vod_class", "vod_remarks"),
                    synopsis(item),
                    canPlayDirectly(item)));
        }
        if (items.isEmpty()) return false;
        // Configuration, directories and login cards still open their normal actions with OK.
        if (!hasFeature) return videos(model.site, model.videos, false);
        Screen owner = screen;
        int selected = Math.min(owner.selection, items.size() - 1);
        homePage = new BoxHomePage(ui, items, selected, new BoxHomePage.Actions() {
            @Override public void select(int position) {
                owner.selection = position;
                JSONObject item = videos.get(position);
                featuredSite = model.site;
                featuredItem = item;
                showHomePreview(owner, model.site, item, position);
            }

            @Override public void play(int position) {
                cancelHomePreview();
                openVideo(model.site, videos.get(position), true);
            }

            @Override public void open(int position) {
                openVideo(model.site, videos.get(position));
            }
        });
        body.addView(homePage, new LinearLayout.LayoutParams(-1, 0, 1));
        return true;
    }

    private static String synopsis(JSONObject item) {
        String summary = metadata(item, "vod_content");
        return summary.isEmpty() ? metadata(item, "vod_blurb") : summary;
    }

    private void showHomePreview(Screen owner, BoxSource.Site site, JSONObject item, int position) {
        cancelHomePreview();
        List<String> pictures = homeArtwork(site, item);
        backdrop(pictures, true);
        if (!canPlayDirectly(item) || music(site, item)) return;
        boolean needsSynopsis = synopsis(item).isEmpty();
        String key = siteKey(site) + "\ndetail\n" + item.optString("vod_id");
        JSONObject cached = pages.get(key);
        if (cached != null) {
            main.post(() -> applyHomeDetails(owner, site, item, position, pictures, cached));
            return;
        }
        if (!needsSynopsis && pictures.size() > 1) return;
        previewRequest = () -> {
            previewRequest = null;
            if (disposed || screen != owner || featuredItem != item || pending != null || !root.isShown()) return;
            BoxSource session = source.fork();
            previewSession = session;
            try {
                previewJob = recommendations.submit(() -> {
                    JSONObject data = null;
                    try {
                        session.select(site);
                        data = session.detail(item.optString("vod_id"));
                    } catch (Exception | LinkageError unavailable) {
                        // The recommendation remains usable when its details are unavailable.
                    } finally {
                        session.close();
                    }
                    JSONObject result = data;
                    main.post(() -> {
                        if (previewSession != session) return;
                        previewSession = null;
                        previewJob = null;
                        if (result != null) pages.put(key, result);
                        applyHomeDetails(owner, site, item, position, pictures, result);
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                session.close();
                previewSession = null;
                applyHomeDetails(owner, site, item, position, pictures, null);
            }
        };
        main.postDelayed(previewRequest, 450);
    }

    private void applyHomeDetails(Screen owner, BoxSource.Site site, JSONObject item, int position,
            List<String> pictures, JSONObject data) {
        if (disposed || screen != owner || featuredItem != item || homePage == null) return;
        JSONArray list = data == null ? null : data.optJSONArray("list");
        JSONObject details = list == null ? null : list.optJSONObject(0);
        String summary = synopsis(item);
        if (summary.isEmpty() && details != null) summary = synopsis(details);
        if (summary.isEmpty() && isTaisui(item)) {
            summary = "青年霍哲为给爷爷治病进入一家神秘公司，接连发生的失踪事件将他卷入一桩跨越数十年的阴谋。";
        }
        homePage.updateDescription(position, summary.isEmpty() ? "暂无影片简介" : summary);
        if (details != null) {
            Set<String> combined = new LinkedHashSet<>(pictures);
            combined.addAll(homeArtwork(site, details));
            if (combined.size() > pictures.size()) backdrop(new ArrayList<>(combined), true);
        }
    }

    private void cancelHomePreview() {
        if (previewRequest != null) main.removeCallbacks(previewRequest);
        previewRequest = null;
        if (previewSession != null) previewSession.cancelRequests();
        previewSession = null;
        if (previewJob != null) {
            previewJob.cancel(true);
            recommendations.purge();
        }
        previewJob = null;
    }

    private static boolean canPlayDirectly(JSONObject item) {
        String id = item.optString("vod_id");
        return !id.isEmpty() && !id.startsWith("msearch:") && isMediaEntry(item);
    }

    private static boolean isMediaEntry(JSONObject item) {
        String tag = item.optString("vod_tag");
        return item.optString("action").isEmpty() && !"action".equals(tag)
                && !"folder".equals(tag) && !"cate".equals(tag);
    }

    private static String shortSiteName(String site) {
        return BlUi.plain(site).replaceFirst("^[^\\p{L}\\p{N}]+", "").split("[|｜丨┃│¦]", 2)[0].trim();
    }

    private Button categoryHeader(SiteHome model, String selected) {
        heading.setVisibility(View.GONE);
        categoryTabs.setVisibility(View.VISIBLE);
        boolean home = screen.tab == Tab.HOME && screen.landing;
        Button recommended = ui.categoryButton("推荐", home);
        recommended.setSelected(selected.isEmpty());
        recommended.setOnClickListener(view -> { if (!home && pending == null) openSite(model.site); });
        if (home) linkHomeHeader(recommended);
        categoryButtons.addView(recommended, new LinearLayout.LayoutParams(-2, ui.px(HEADER_ITEM_HEIGHT)));
        Button active = recommended;
        for (int i = 0; model.categories != null && i < Math.min(20, model.categories.length()); i++) {
            JSONObject item = model.categories.optJSONObject(i);
            if (item == null || item.optString("type_id").isEmpty()) continue;
            String id = item.optString("type_id"), name = item.optString("type_name", id);
            Button category = ui.categoryButton(name, home);
            if (home) linkHomeHeader(category);
            category.setSelected(id.equals(selected));
            if (category.isSelected()) active = category;
            category.setOnClickListener(view -> listing(model.site, name, id, "", 1, true));
            categoryButtons.addView(category, new LinearLayout.LayoutParams(-2, ui.px(HEADER_ITEM_HEIGHT)));
        }
        Button selectedButton = active;
        categoryTabs.post(() -> {
            if (selectedButton.getParent() == categoryButtons && !categoryTabs.hasFocus()) {
                categoryTabs.scrollTo(Math.max(0, selectedButton.getRight() - categoryTabs.getWidth()), 0);
            }
        });
        return active;
    }

    private FrameLayout featurePanel(BoxSource.Site site, JSONObject video, String actionLabel, Runnable open) {
            LinearLayout info = ui.vertical(), buttons = ui.horizontal();
            TextView[] labels=new TextView[3];
            ImageView background = ui.imageView();background.setScaleType(ImageView.ScaleType.FIT_CENTER);background.setBackgroundDrawable(ui.fill(0,0));
            FrameLayout hero = new FrameLayout(context) {
                private int appearance=-1;
                @Override protected void onMeasure(int width, int height) {
                    int w=View.MeasureSpec.getSize(width),h=View.MeasureSpec.getSize(height);
                    int posterWidth=Math.min(ui.px(210),w/4);
                    info.getLayoutParams().width = Math.min(Math.max(1,w-posterWidth-ui.px(48)), ui.px(680));
                    background.getLayoutParams().width=posterWidth;
                    int mode=h<ui.px(260)?0:h<ui.px(340)?1:2,key=mode*16+(h<ui.px(180)?1:0)+(h<ui.px(190)?2:0)+(h<ui.px(230)?4:0)+(h<ui.px(300)?8:0);
                    if(key!=appearance&&labels[0]!=null){appearance=key;int font=mode==0?(h<ui.px(180)?48:56):68;if(labels[0].length()>10)font=Math.max(44,font-12);labels[0].setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,ui.px(font));labels[0].setMaxLines(1);labels[1].setMaxLines(h<ui.px(300)?1:2);labels[1].setVisibility(h<ui.px(230)?View.GONE:View.VISIBLE);labels[2].setVisibility(h<ui.px(190)?View.GONE:View.VISIBLE);info.setPadding(ui.px(32),ui.px(mode==0?8:20),ui.px(24),ui.px(mode==0?8:20));}
                    boolean summaryShown=labels[1]!=null&&labels[1].getParent()!=null&&labels[1].getVisibility()==View.VISIBLE;
                    int gap=h<ui.px(180)?10:h<ui.px(220)||h<ui.px(300)&&summaryShown?12:24;
                    ((LinearLayout.LayoutParams)buttons.getLayoutParams()).topMargin=ui.px(gap);
                    super.onMeasure(width, height);
                }
            };
            hero.setBackgroundDrawable(ui.glass(0xaa253844, 24));
            art.load(background, artwork(site, video));
            FrameLayout.LayoutParams poster=new FrameLayout.LayoutParams(ui.px(210),-1,Gravity.RIGHT|Gravity.CENTER_VERTICAL);
            poster.setMargins(0,ui.px(24),ui.px(24),ui.px(24));hero.addView(background,poster);
            info.setGravity(Gravity.CENTER_VERTICAL);
            info.setPadding(ui.px(32), ui.px(20), ui.px(24), ui.px(20));
            String facts=metadata(video,"vod_class","vod_year","vod_remarks");
            String sourceName=BlUi.plain(site.name).replaceFirst("^[^\\p{L}\\p{N}]+","").split("[|｜丨┃│¦]",2)[0].trim();
            TextView eyebrow = ui.text(sourceName+(facts.isEmpty()?"":"  ·  "+facts), 21, BoxUi.SUB);
            labels[2]=eyebrow;
            eyebrow.setSingleLine(true); eyebrow.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(eyebrow, new LinearLayout.LayoutParams(-1, ui.px(30)));
            TextView title = ui.displayTitle(videoName(video));
            labels[0]=title;
            if(videoName(video).codePointCount(0,videoName(video).length())>10)title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,ui.px(64));
            title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(title, new LinearLayout.LayoutParams(-1, -2));
            String synopsis = BlUi.plain(video.optString("vod_content")).trim();
            TextView summary = ui.text(synopsis.isEmpty() ? metadata(video, "vod_area", "vod_class", "vod_actor") : synopsis, 22, BoxUi.TEXT);
            labels[1]=summary;
            summary.setMaxLines(2); summary.setEllipsize(TextUtils.TruncateAt.END);
            summary.setPadding(0, ui.px(10), 0, 0);
            if (!summary.getText().toString().isEmpty()) info.addView(summary);
            Button details = action(buttons, actionLabel, open);
            details.setBackgroundDrawable(ui.primaryFocus(details)); details.setTextColor(ui.controlText());
            details.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,ui.px(26));
            details.setMinHeight(ui.px(60));
            Button secondary=video.optString("vod_id").isEmpty()&&currentSite!=null?action(buttons,"浏览分类",()->switchTab(Tab.CATEGORIES)):action(buttons,"搜索同名",()->chooseSearchSite(videoName(video)));
            secondary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,ui.px(26));secondary.setMinHeight(ui.px(60));
            LinearLayout.LayoutParams actions = new LinearLayout.LayoutParams(-1, -2);
            actions.topMargin=ui.px(24);
            info.addView(buttons,actions);
            hero.addView(info, new FrameLayout.LayoutParams(-1, -1));
            primary = details;
            return hero;
    }

    private void openVideo(BoxSource.Site site, JSONObject item) {
        openVideo(site, item, false);
    }

    private void openVideo(BoxSource.Site site, JSONObject item, boolean direct) {
        String itemId = item.optString("vod_id");
        if (itemId.isEmpty() || itemId.startsWith("msearch:")) {
            chooseSearchSite(itemId.startsWith("msearch:") ? itemId.substring(8) : videoName(item)); return;
        }
        String command = item.optString("action");
        if (command.isEmpty() && "action".equals(item.optString("vod_tag"))) command = itemId;
        if (!command.isEmpty()) {
            String label = videoName(item).trim();
            runSiteAction(site, command, "登入自己网盘".equals(label) || "登录自己网盘".equals(label));
        }
        else if ("folder".equals(item.optString("vod_tag")) || "cate".equals(item.optString("vod_tag")))
            listing(site, videoName(item), itemId, "", 1, true);
        else detail(site, item, direct);
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
            BoxNineKeyboard keyboard=new BoxNineKeyboard(ui,input);body.addView(keyboard,new LinearLayout.LayoutParams(Math.min(ui.px(620),context.getResources().getDisplayMetrics().widthPixels-ui.px(230)),-2));
            primary=keyboard.entry();
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
        addGrid(body, cards);
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
        cancelRecommendations();
        SiteHome cached=currentSite!=null&&currentSite.site==site?currentSite:homes.get(siteKey(site));
        SiteHome model=cached==null?new SiteHome(site,null,null,null,""):cached;
        JSONObject values;
        try{values=search.isEmpty()?filterValues(site,type,model.filters):new JSONObject();}
        catch(Exception invalid){message("筛选条件无效");return;}
        String cacheKey=siteKey(site)+"\n"+type+"\n"+page+"\n"+values;
        JSONObject ready=search.isEmpty()?pages.get(cacheKey):null;
        if(ready!=null){showListing(model,label,type,search,values,page,ready,push);return;}
        load("正在加载列表…", () -> {
            source.select(site);
            JSONObject result=search.isEmpty()?source.category(type,page,values):source.search(search,page);
            return () -> {if(search.isEmpty())pages.put(cacheKey,result);showListing(model,label,type,search,values,page,result,push);};
        });
    }
    private void showListing(SiteHome model, String label, String type, String search,
            JSONObject filters, int page, JSONObject result, boolean push) {
        BoxSource.Site site = model.site;
        boolean category = search.isEmpty();
        List<JSONObject> rows = videoItems(result.optJSONArray("list"));
        Set<String> seen = new HashSet<>();
        for (JSONObject row : rows) seen.add(videoKey(row));
        int[] nextPage = {page + 1};
        int[] lastPage = {result.optInt("pagecount", page + 1)};
        currentSite = model;
        show(new Screen(category ? Tab.CATEGORIES : Tab.SEARCH, label, false, () -> {
            if (category) {
                Button active = categoryHeader(model, type);
                View filter = filterBar(site, label, type, model.filters);
                if (filter != null) {
                    filter.setVisibility(View.GONE);
                    active.setOnLongClickListener(view -> {
                        filter.setVisibility(filter.isShown() ? View.GONE : View.VISIBLE);
                        return true;
                    });
                }
            }
            if (rows.isEmpty()) {
                message("没有匹配内容");
                return;
            }
            GridView cards = videoGrid(site, rows, true);
            Runnable more = () -> {
                if (pending != null || cards != grid || nextPage[0] > lastPage[0]) return;
                int requested = nextPage[0];
                boolean remote = cards.hasFocus() && !cards.isInTouchMode();
                String key = siteKey(site) + "\n" + type + "\n" + requested + "\n" + filters;
                load("正在加载列表…", () -> {
                    JSONObject data = category ? pages.get(key) : null;
                    if (data == null) {
                        source.select(site);
                        data = category ? source.category(type, requested, filters) : source.search(search, requested);
                    }
                    JSONObject response = data;
                    List<JSONObject> incoming = videoItems(data.optJSONArray("list"));
                    return () -> {
                        if (category) pages.put(key, response);
                        int before = rows.size();
                        for (JSONObject row : incoming) {
                            if (seen.add(videoKey(row))) rows.add(row);
                        }
                        nextPage[0] = requested + 1;
                        lastPage[0] = before == rows.size() ? requested : response.optInt("pagecount", requested + 1);
                        if (rows.size() == before) return;
                        ((BaseAdapter) cards.getAdapter()).notifyDataSetChanged();
                        if (remote) cards.setSelection(before);
                    };
                });
            };
            cards.setOnKeyListener((view, code, event) -> {
                int selected = cards.getSelectedItemPosition();
                int columns = Math.max(1, cards.getNumColumns());
                if (code != KeyEvent.KEYCODE_DPAD_DOWN || event.getAction() != KeyEvent.ACTION_DOWN
                        || event.getRepeatCount() != 0 || selected < 0
                        || selected / columns < (rows.size() - 1) / columns
                        || nextPage[0] > lastPage[0]) return false;
                more.run();
                return true;
            });
            cards.setOnTouchListener(new View.OnTouchListener() {
                private float down;

                @Override public boolean onTouch(View view, android.view.MotionEvent event) {
                    if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) down = event.getY();
                    else if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP
                            && down - event.getY() > ui.px(32) && !cards.canScrollVertically(1)) more.run();
                    return false;
                }
            });
        }), push);
    }

    private static String videoKey(JSONObject item) {
        String id = item.optString("vod_id");
        return id.isEmpty() ? videoName(item) : id;
    }

    private boolean videos(BoxSource.Site site, JSONArray items) { return videos(site, items, true); }
    private static List<JSONObject> videoItems(JSONArray items) {
        List<JSONObject> videos = new ArrayList<>();
        for (int i = 0; items != null && i < Math.min(160, items.length()); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && (!item.optString("vod_id").isEmpty() || !item.optString("vod_name").isEmpty())) videos.add(item);
        }
        return videos;
    }

    private boolean videos(BoxSource.Site site, JSONArray items, boolean poster) {
        List<JSONObject> videos = videoItems(items);
        if (videos.isEmpty()) return false;
        videoGrid(site, videos, poster);
        return true;
    }

    private GridView videoGrid(BoxSource.Site site, List<JSONObject> videos, boolean poster) {
        if (poster) {
            String firstCover = artwork(site, videos.get(0));
            if (!firstCover.isEmpty()) backdrop(Collections.singletonList(firstCover), false);
        }
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
        addGrid(body, cards);
        return cards;
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

    private View filterBar(BoxSource.Site site, String label, String category, JSONObject filters) {
        JSONArray fields = filters == null ? null : filters.optJSONArray(category);
        if (fields == null || fields.length() == 0) return null;
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = ui.horizontal();
        scroll.addView(row);
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
        if (row.getChildCount() == 0) return null;
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        return scroll;
    }

    private void runSiteAction(BoxSource.Site site, String command, boolean cloudLogin) {
        homes.remove(siteKey(site));pages.clear();cancelRecommendations();
        cloudLoginAction = cloudLogin ? new CloudLoginAction(site, command, screen) : null;
        authorizationFeedbackUntil = android.os.SystemClock.uptimeMillis() + 5 * 60 * 1000L;
        load("正在处理站点操作…", () -> {
            source.select(site);
            JSONObject result = source.action(command);
            String text = result.optString("toast");
            return () -> message(text);
        });
    }

    private void detail(BoxSource.Site site, JSONObject item) {
        detail(site, item, false);
    }

    private void detail(BoxSource.Site site, JSONObject item, boolean direct) {
        Tab from = screen.tab;
        load("正在加载详情…", () -> {
            String cacheKey=siteKey(site)+"\ndetail\n"+item.optString("vod_id");JSONObject data=pages.get(cacheKey);
            if(data==null){source.select(site);data=source.detail(item.optString("vod_id"));}
            JSONArray list = data.optJSONArray("list");
            if (direct) {
                List<PlayLine> lines = firstPlayableDetails(site, list, item.optString("vod_id"));
                JSONObject response = data;
                return () -> {
                    pages.put(cacheKey, response);
                    lines.get(0).episodes.get(0).action.run();
                };
            }
            JSONObject video = list == null ? null : list.optJSONObject(0);
            if (video == null) {
                source.select(site);
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
            JSONObject response=data;
            return () -> {pages.put(cacheKey,response);show(new Screen(from, title, false, () -> {
                detailPage(site, video, image, lines, selected, music(site, video));
            }), true);};
        });
    }

    private List<PlayLine> firstPlayableDetails(BoxSource.Site site, JSONArray items, String id) throws IOException {
        List<PlayLine> fallback = null;
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject video = items.optJSONObject(i);
            if (video == null) continue;
            List<PlayLine> lines = playLines(site, video);
            if (lines.isEmpty()) continue;
            if (id.equals(video.optString("vod_id"))) return lines;
            if (fallback == null) fallback = lines;
        }
        if (fallback != null) return fallback;
        throw new IOException("站点没有返回可播放内容");
    }

    private static boolean music(BoxSource.Site site, JSONObject video) {
        return MusicSearch.supports(site.api) || site.name.contains("音乐")
                || video.optString("vod_type").contains("音乐");
    }

    private void detailPage(BoxSource.Site site, JSONObject video, String image, List<PlayLine> lines, int[] selected, boolean music) {
        if (!music) {
            if (currentSite != null) categoryHeader(currentSite, "");
            Runnable start = lines.isEmpty() ? () -> message("站点没有提供播放线路") : lines.get(selected[0]).episodes.get(0).action;
            LinearLayout episodes = ui.vertical();
            body.addView(new BoxPageLayout(ui,featurePanel(site,video,"▶  开始播放",start),episodes),new LinearLayout.LayoutParams(-1,0,1));
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
                if (id.trim().isEmpty()) continue;
                String title = music(site, video) ? BlUi.plain(name) : videoName(video) + " · " + name;
                int index = line.episodes.size();
                line.episodes.add(new Choice(name, () -> play(site, flag, id, title, line, index)));
                if (++episodeCount >= 500) break;
            }
            if (!line.episodes.isEmpty()) lines.add(line);
        }
        return lines;
    }

    private void episodePanel(LinearLayout parent, List<PlayLine> lines, int[] selected, boolean music) {
        LinearLayout header = ui.horizontal(), channels = ui.horizontal(), panel = ui.vertical();
        TextView section=ui.title(music?"歌曲":"选集");
        section.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,ui.px(26));
        header.addView(section,new LinearLayout.LayoutParams(-2,-2));
        TextView count = ui.text("", 16, BlUi.SUB);
        count.setPadding(ui.px(12), 0, ui.px(4), 0);
        header.addView(count, new LinearLayout.LayoutParams(-2, -2));
        header.addView(new View(context),new LinearLayout.LayoutParams(0,1,1));
        channels.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);header.addView(channels,new LinearLayout.LayoutParams(-2,-2));
        LinearLayout.LayoutParams headerSize=new LinearLayout.LayoutParams(-1,ui.px(56));headerSize.bottomMargin=ui.px(12);parent.addView(header,headerSize);
        TextView fullTitle = ui.text("", 17, BlUi.SUB);
        fullTitle.setSingleLine(true); fullTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        fullTitle.setMarqueeRepeatLimit(-1); fullTitle.setSelected(true);
        fullTitle.setVisibility(music?View.VISIBLE:View.GONE);
        parent.addView(fullTitle, new LinearLayout.LayoutParams(-1, ui.px(40)));
        parent.addView(panel, new LinearLayout.LayoutParams(-1, 0, 1));
        Runnable bind = () -> {
            List<Choice> episodes = lines.get(selected[0]).episodes;
            boolean files=false;if(!music)for(Choice item:episodes)if(BoxEpisodeLabel.file(item.title))files=true;
            section.setText(music?"歌曲":files?"播放文件":"选集");
            count.setText((music?"":"共 ")+episodes.size() + (music ? " 首" : files?" 个":" 集"));
            panel.removeAllViews();
            episodeChoices(panel, episodes, music, fullTitle);
        };
        String route="线路 · "+lines.get(selected[0]).name;
        if(lines.size()>1){
            Screen owner=screen;
            Button choose=action(channels,route+"  ▾",()->{
                List<Choice> routes=new ArrayList<>();
                for(int i=0;i<lines.size();i++){int index=i;routes.add(new Choice((index==selected[0]?"✓  ":"")+lines.get(index).name,()->{
                    selected[0]=index;owner.selection=0;if(!history.isEmpty()&&history.peek()==owner)history.pop();show(owner,false);
                }));}
                show(new Screen(owner.tab,"选择播放线路",false,()->choices(body,routes,3,64)),true);
            });
            choose.setMaxWidth(ui.px(320));choose.setEllipsize(TextUtils.TruncateAt.END);choose.setContentDescription("切换播放线路");
        }else{
            TextView name=ui.text(route,20,BoxUi.SUB);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);name.setMaxWidth(ui.px(320));channels.addView(name);
        }
        bind.run();
    }

    private void episodeChoices(LinearLayout parent, List<Choice> items, boolean music, TextView fullTitle) {
        boolean longNames = music;
        boolean fileNames=false;
        for (Choice item : items) if (item.title.codePointCount(0, item.title.length()) > 14) longNames = true;
        if(!music)for(Choice item:items)if(BoxEpisodeLabel.file(item.title))fileNames=true;
        final boolean files=fileNames;
        final int columns = files?1:longNames ? 3 : 6, minWidth = files?1:longNames ? 230 : 110;
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
        options.setPadding(ui.px(4), ui.px(4), ui.px(4), ui.px(16)); options.setClipToPadding(false);
        options.setSelector(ui.capsuleSelector()); options.setDrawSelectorOnTop(true);
        options.setAdapter(new BaseAdapter() {
            public int getCount() { return items.size(); }
            public Choice getItem(int position) { return items.get(position); }
            public long getItemId(int position) { return position; }
            public View getView(int position, View reuse, ViewGroup container) {
                LinearLayout item = reuse instanceof LinearLayout ? (LinearLayout) reuse : ui.horizontal();
                if (reuse == null) {
                    item.setPadding(ui.px(files?22:12), ui.px(files?14:9), ui.px(files?22:12), ui.px(files?14:9));
                    item.setMinimumHeight(ui.px(files?90:columns == 3 ? 76 : 60));
                    item.setBackgroundDrawable(ui.rowGlass(item));
                    TextView number = ui.text("", 15, BoxUi.SUB);
                    number.setTextColor(ui.episodeText());number.setDuplicateParentStateEnabled(true);
                    number.setVisibility(columns==6?View.GONE:View.VISIBLE);
                    item.addView(number, new LinearLayout.LayoutParams(ui.px(files?44:34), -1));
                    LinearLayout labels=ui.vertical();
                    TextView name = ui.text("", 22, BlUi.TEXT);
                    name.setTextColor(ui.episodeText());
                    name.setDuplicateParentStateEnabled(true);
                    name.setGravity((columns==6?Gravity.CENTER:Gravity.LEFT)|Gravity.CENTER_VERTICAL);
                    name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
                    labels.addView(name,new LinearLayout.LayoutParams(-1,-2));
                    TextView facts=ui.text("",17,BoxUi.SUB);facts.setPadding(0,ui.px(6),0,0);facts.setSingleLine(true);facts.setEllipsize(TextUtils.TruncateAt.END);labels.addView(facts,new LinearLayout.LayoutParams(-1,-2));
                    item.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                }
                Choice choice = getItem(position);
                ((TextView) item.getChildAt(0)).setText(String.format(java.util.Locale.US, "%02d", position + 1));
                LinearLayout labels=(LinearLayout)item.getChildAt(1);BoxEpisodeLabel label=BoxEpisodeLabel.of(choice.title);
                ((TextView)labels.getChildAt(0)).setText(files?label.title:prefix.isEmpty() ? choice.title : choice.title.substring(prefix.length()));
                TextView facts=(TextView)labels.getChildAt(1);facts.setText(label.facts);facts.setVisibility(files&&!label.facts.isEmpty()?View.VISIBLE:View.GONE);
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
        if(files)options.setOnItemLongClickListener((owner,view,position,id)->{new android.app.AlertDialog.Builder(context).setTitle("完整文件名").setMessage(items.get(position).title).setPositiveButton("关闭",null).show();return true;});
        fullTitle.setText(items.get(Math.min(screen.selection, items.size() - 1)).title);
        addGrid(parent, options);
    }

    private void play(BoxSource.Site site, String flag, String id, String title, PlayLine line, int index) {
        cloudLoginAction = null;
        authorizationFeedbackUntil = 0;
        load("正在解析播放地址…", () -> {
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
            if (dash && android.os.Build.VERSION.SDK_INT < 16) {
                return () -> compatibilityToast("DASH 播放需要 Android 4.1 或更新系统");
            }
            if (dash && !(host instanceof ChannelPlugin.PlayerUiHost)) {
                return () -> compatibilityToast("播放 DASH 需要更新 nTv");
            }
            return () -> {
                if (dashPlayer != null) closeDashPlayer();
                playbackLine = line;
                playbackEpisode = index;
                if (dash) {
                    try {
                        dashPlayer = new BoxDashPlayer(ui, (ChannelPlugin.PlayerUiHost) host, http,
                                source, url, mediaHeaders, title, () -> {
                                    if (!showEpisodes()) closeDashPlayer();
                                });
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

    @Override public boolean showEpisodes() {
        if (disposed || !(context instanceof Activity) || playbackLine == null) return false;
        if (episodeDialog != null) return true;
        PlayLine line = playbackLine;
        int playing = playbackEpisode;
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = ui.vertical();
        panel.setPadding(ui.px(20), ui.px(16), ui.px(20), ui.px(16));
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xf4384956, 0xfa101e29});
        glass.setCornerRadius(ui.px(24));
        glass.setStroke(Math.max(1, ui.px(1)), 0x779db5c7);
        panel.setBackgroundDrawable(glass);
        TextView heading = ui.text("选集", 24, BlUi.TEXT);
        heading.setPadding(ui.px(14), 0, ui.px(14), 0);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, ui.px(42)));
        TextView route = ui.text(line.name + "  ·  " + line.episodes.size() + " 项", 16, BoxUi.SUB);
        route.setSingleLine(true);
        route.setEllipsize(TextUtils.TruncateAt.END);
        route.setPadding(ui.px(14), 0, ui.px(14), 0);
        panel.addView(route, new LinearLayout.LayoutParams(-1, ui.px(34)));

        int rowHeight = ui.px(46);
        ListView options = new ListView(context);
        options.setDivider(null);
        options.setSelector(ui.capsuleSelector());
        options.setDrawSelectorOnTop(true);
        options.setContentDescription("剧集列表");
        options.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return line.episodes.size(); }
            @Override public Choice getItem(int position) { return line.episodes.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View reuse, ViewGroup parent) {
                LinearLayout row = reuse instanceof LinearLayout ? (LinearLayout) reuse : ui.horizontal();
                if (reuse == null) {
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(ui.px(14), 0, ui.px(14), 0);
                    row.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, rowHeight));
                    TextView title = ui.text("", 20, BlUi.TEXT);
                    title.setSingleLine(true);
                    title.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                    TextView marker = ui.text("在播", 16, 0xffb9e6ff);
                    marker.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                    row.addView(marker, new LinearLayout.LayoutParams(ui.px(54), -1));
                }
                ((TextView) row.getChildAt(0)).setText(getItem(position).title);
                row.getChildAt(1).setVisibility(position == playing ? View.VISIBLE : View.INVISIBLE);
                return row;
            }
        });
        options.setOnItemClickListener((parent, row, position, id) -> {
            dialog.dismiss();
            if (position != playing) line.episodes.get(position).action.run();
        });
        int listHeight = Math.min(line.episodes.size() * rowHeight,
                Math.min(ui.px(360), Math.max(rowHeight, container.getHeight() - ui.px(144))));
        panel.addView(options, new LinearLayout.LayoutParams(-1, listHeight));
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setDimAmount(0.4f);
        }
        dialog.setOnKeyListener((owner, key, event) -> {
            if (key != KeyEvent.KEYCODE_BUTTON_A && key != KeyEvent.KEYCODE_BUTTON_SELECT
                    && key != KeyEvent.KEYCODE_NUMPAD_ENTER) return false;
            int selected = options.getSelectedItemPosition();
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled() && selected >= 0) {
                options.performItemClick(options.getSelectedView(), selected, selected);
            }
            return true;
        });
        dialog.setOnDismissListener(owner -> episodeDialog = null);
        dialog.show();
        episodeDialog = dialog;
        if (window != null) window.setLayout(Math.min(container.getWidth() - ui.px(48), ui.px(560)), -2);
        options.requestFocusFromTouch();
        options.setSelection(playing);
        return true;
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
        options.setHorizontalSpacing(ui.px(12));options.setVerticalSpacing(ui.px(10));
        options.setSelector(ui.capsuleSelector());
        options.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Choice getItem(int position) { return items.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View reuse, ViewGroup container) {
                TextView item = reuse instanceof TextView ? (TextView) reuse : ui.text("", 22, BlUi.TEXT);
                if (reuse == null) {
                    item.setGravity(Gravity.CENTER);
                    item.setPadding(ui.px(12), ui.px(8), ui.px(12), ui.px(8));
                    item.setMinHeight(ui.px(height));
                    item.setCompoundDrawables(null,null,null,null);
                    item.setMaxLines(2);
                    item.setEllipsize(TextUtils.TruncateAt.END);
                    item.setBackgroundDrawable(ui.rowGlass(item));
                }
                item.setText(getItem(position).title);
                return item;
            }
        });
        options.setOnItemClickListener((container, view, index, id) -> {
            screen.selection = index;
            items.get(index).action.run();
        });
        addGrid(parent, options);
    }

    private void addGrid(LinearLayout parent, GridView content) {
        grid = content;
        primary = grid;
        parent.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private Button homeHeaderAction(LinearLayout header, String label, int icon, Runnable action) {
        Button button = ui.homeButton(label, false);
        if (icon >= 0) {
            Drawable glyph = ui.navigationIcon(icon);
            glyph.setBounds(0, 0, ui.px(26), ui.px(26));
            button.setCompoundDrawables(glyph, null, null, null);
            button.setCompoundDrawablePadding(ui.px(7));
        }
        button.setOnClickListener(view -> action.run());
        header.addView(button, new LinearLayout.LayoutParams(-2, ui.px(HEADER_ITEM_HEIGHT)));
        return button;
    }

    private void linkHomeHeader(Button button) {
        button.setOnKeyListener((view, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_DOWN || homePage == null) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) homePage.focusFeature();
            return true;
        });
    }

    private void openHomeStations() {
        if (stations == null || stations.stationChoices.isEmpty()) {
            switchTab(Tab.SITES);
            return;
        }
        openHomeMenu("切换站点", stations.stationChoices, null, true);
    }

    private void openFavorites() {
        List<Choice> rows = new ArrayList<>();
        BoxSource.Site site = featuredSite;
        JSONObject item = featuredItem;
        boolean offerToggle = site != null && item != null && canPlayDirectly(item);
        if (offerToggle) {
            boolean saved = favorites.find(siteKey(site), item.optString("vod_id")) != null;
            rows.add(new Choice((saved ? "取消收藏 · " : "收藏 · ") + videoName(item), () -> {
                favorites.toggle(siteKey(site), shortSiteName(site.name), item);
                toast(saved ? "已取消收藏" : "已收藏");
                openFavorites();
            }));
        }
        List<BoxFavorites.Entry> entries = favorites.list();
        for (BoxFavorites.Entry entry : entries) {
            rows.add(new Choice(videoName(entry.item) + "  ·  " + entry.siteName, () -> {
                BoxSource.Site available = knownSites.get(entry.siteKey);
                if (available != null) openVideo(available, entry.item, true);
                else toast("请先打开该收藏所属的站点配置");
            }));
        }
        if (rows.isEmpty()) rows.add(new Choice("暂无收藏", () -> { }));
        int offset = offerToggle ? 1 : 0;
        openHomeMenu(entries.isEmpty() ? "收藏" : "收藏 · 长按 OK 移除", rows, index -> {
            int savedIndex = index - offset;
            if (savedIndex < 0 || savedIndex >= entries.size()) return false;
            favorites.remove(entries.get(savedIndex));
            toast("已移除收藏");
            openFavorites();
            return true;
        }, false);
    }

    private interface Hold { boolean run(int index); }

    private void openHomeMenu(String label, List<Choice> items, Hold hold, boolean stationPicker) {
        if (homeMenu != null) homeMenu.dismiss();
        Dialog menu = new Dialog(context);
        menu.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = ui.vertical();
        panel.setPadding(ui.px(14), ui.px(12), ui.px(14), ui.px(14));
        panel.setBackgroundDrawable(ui.glass(0xf52d3d46, 21));
        TextView labelView = ui.title(label);
        labelView.setTextColor(BoxUi.TEXT);
        labelView.setPadding(ui.px(12), 0, 0, 0);
        panel.addView(labelView, new LinearLayout.LayoutParams(-1, ui.px(48)));
        ListView list = new ListView(context);
        list.setDivider(null);
        list.setSelector(ui.capsuleSelector());
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Choice getItem(int index) { return items.get(index); }
            @Override public long getItemId(int index) { return index; }
            @Override public View getView(int index, View old, ViewGroup parent) {
                TextView row = old instanceof TextView ? (TextView) old : ui.text("", 23, BoxUi.TEXT);
                row.setPadding(ui.px(12), 0, ui.px(12), 0);
                row.setSingleLine(true);
                row.setEllipsize(TextUtils.TruncateAt.END);
                row.setMinHeight(ui.px(48));
                row.setText(getItem(index).title);
                return row;
            }
        });
        list.setOnItemClickListener((parent, view, index, id) -> {
            menu.dismiss();
            items.get(index).action.run();
        });
        if (hold != null) list.setOnItemLongClickListener((parent, view, index, id) -> hold.run(index));
        int height = Math.min(ui.px(48) * items.size(), Math.max(ui.px(48), container.getHeight() - ui.px(180)));
        panel.addView(list, new LinearLayout.LayoutParams(-1, height));
        menu.setContentView(panel);
        menu.setOnDismissListener(dialog -> { if (homeMenu == menu) homeMenu = null; });
        Window window = menu.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            if (stationPicker) {
                window.setGravity(Gravity.TOP | Gravity.LEFT);
                WindowManager.LayoutParams params = window.getAttributes();
                params.x = ui.px(34);
                params.y = ui.px(68);
                window.setAttributes(params);
            }
        }
        homeMenu = menu;
        menu.show();
        if (window != null) window.setLayout(Math.min(container.getWidth() - ui.px(70),
                ui.px(stationPicker ? 400 : 570)), -2);
        list.requestFocusFromTouch();
        list.setSelection(0);
    }

    private void toast(String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    private Button action(LinearLayout parent, String label, Runnable run) {
        Button button = ui.button(label);
        int icon=label.startsWith("▶")?7:label.equals("搜索同名")?3:-1;
        if(icon>=0){
            button.setText(label.startsWith("▶")?label.substring(1).trim():label);
            Drawable glyph=ui.actionIcon(icon);glyph.setBounds(0,0,ui.px(24),ui.px(24));
            button.setCompoundDrawables(glyph,null,null,null);button.setCompoundDrawablePadding(ui.px(8));
            button.setContentDescription(button.getText());
        }
        button.setAllCaps(false);
        button.setOnClickListener(view -> run.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
        size.rightMargin = ui.px(12);
        parent.addView(button, size);
        return button;
    }

    private void show(Screen next, boolean push) {
        cancelHomePreview();
        if(screen!=null&&screen!=next)cancelRecommendations();
        if (screen != null && screen != next) cancelSearch(screen.search);
        if (push && screen != null) {
            saveSelection();
            history.push(screen);
            if (history.size() > MAX_HISTORY) history.removeLast();
        }
        screen = next;
        boolean home = next.tab == Tab.HOME && next.landing;
        homeSite.setVisibility(home ? View.VISIBLE : View.GONE);
        homeSearch.setVisibility(home || next.tab == Tab.SITES ? View.VISIBLE : View.GONE);
        homeFavorites.setVisibility(home ? View.VISIBLE : View.GONE);
        root.setPadding(ui.px(34), ui.px(15), ui.px(34), ui.px(24));
        homeSite.setText(currentSite == null ? "站点  ⌄" : shortSiteName(currentSite.site.name) + "  ⌄");
        ambientBackdrop();
        if (next.tab == Tab.SITES) stations = next;
        heading.setText(next.title);
        heading.setVisibility(View.VISIBLE);
        categoryTabs.setVisibility(View.GONE);
        categoryButtons.removeAllViews();
        message("");
        actions.removeAllViews();
        body.removeAllViews();
        homePage = null;
        featuredSite = null;
        featuredItem = null;
        grid = null;
        primary = null;
        next.render.run();
        focusContent();
        root.post(()->{if(!disposed&&screen==next&&root.isShown()&&dashPlayer==null)focusContent();});
    }

    private void ambientBackdrop() {
        backdropArt.setVisibility(View.GONE);
        backdropShade.setBackgroundDrawable(BoxBackdrop.scrim(false));
        backdropShade.setVisibility(View.VISIBLE);
    }

    private void backdrop(List<String> urls,boolean cinema) {
        if(cinema){
            backdropArt.setVisibility(View.VISIBLE);
            backdropShade.setBackgroundDrawable(BoxBackdrop.scrim(true));
            backdropShade.setVisibility(View.VISIBLE);
        }else ambientBackdrop();
        int edge = cinema ? Math.min(1440, Math.round(Math.max(context.getResources().getDisplayMetrics().widthPixels,
                context.getResources().getDisplayMetrics().heightPixels) * 1.25f)) : art.thumbnailEdge();
        String key = cinema + "\n" + edge + "\n" + TextUtils.join("\n", urls);
        if (key.equals(backdropKey) && backdropArt.getDrawable() != null) return;
        backdropKey = key;
        int request = ++backdropGeneration;
        if (cinema && urls.isEmpty()) {
            ambientArt.setTag(null);
            ambientArt.setImageDrawable(null);
        }
        BoxArt.Ready ready = bitmap -> {
            if (key.equals(ambientArt.getTag())) return;
            art.frost(bitmap, blurred -> {
                if (disposed || request != backdropGeneration) return;
                ambientArt.setTag(key);
                ambientArt.setImageBitmap(blurred);
                controlGlass.image(blurred);
            });
        };
        if (cinema) art.loadBackdrop(backdropArt, urls, edge, ready);
        else art.load(backdropArt, urls.isEmpty() ? "" : urls.get(0), edge, ready);
    }

    private void saveSelection() {
        if (grid != null && grid.getSelectedItemPosition() >= 0) {
            screen.selection = grid.getSelectedItemPosition();
        }
    }

    private void focusContent() {
        if (homePage != null && homePage.focusFeature()) {
            return;
        } else if (primary != null && primary != grid && primary.isShown() && primary.requestFocusFromTouch()) {
            return;
        } else if (grid != null && grid.isShown() && grid.getCount() > 0) {
            grid.requestFocusFromTouch();
            grid.setSelection(Math.min(screen.selection, grid.getCount() - 1));
        } else {
            if (homeSite.isShown()) homeSite.requestFocus();
            else if (categoryTabs.isShown() && categoryButtons.getChildCount() > 0)
                categoryButtons.getChildAt(0).requestFocus();
            else if (homeSearch.isShown()) homeSearch.requestFocus();
        }
    }

    private void load(String title, Load operation) {
        if (disposed || pending != null) return;
        int request = ++generation;
        pending = worker.submit(() -> {
            try {
                Runnable result = operation.run();
                main.post(() -> {
                    if (disposed || generation != request) return;
                    finishLoading();
                    result.run();
                });
            } catch (Exception | LinkageError error) {
                main.post(() -> {
                    if (disposed || generation != request) return;
                    finishLoading();
                    String compatibility = unsupportedAndroid(error);
                    if (compatibility != null) {
                        compatibilityToast(compatibility);
                        return;
                    }
                    String detail = error instanceof IOException ? error.getMessage() : error.getClass().getSimpleName();
                    message("加载失败 · " + (detail == null ? "请检查站点" : detail));
                    if (!root.isShown() && playbackLine != null) host.status(status.getText().toString());
                });
            }
        });
        showLoading(title);
    }

    private static String unsupportedAndroid(Throwable error) {
        if (android.os.Build.VERSION.SDK_INT >= 19) return null;
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error instanceof NoClassDefFoundError || error instanceof ClassNotFoundException) {
                String symbol = error.getMessage();
                if ("java.util.Objects".equals(symbol) || "java/util/Objects".equals(symbol)
                        || "Ljava/util/Objects;".equals(symbol)) {
                    return "此站点需要 Android 4.4 或更新系统";
                }
            }
        }
        return null;
    }

    private void compatibilityToast(String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    private void showLoading(String title) {
        if (context instanceof Activity && !((Activity) context).isFinishing()) {
            Dialog dialog = new Dialog(context) {
                @Override public void onBackPressed() {
                    if (loadingDialog == this && pending != null) cancelPending();
                }
            };
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCanceledOnTouchOutside(false);

            LinearLayout panel = ui.vertical();
            panel.setPadding(ui.px(24), ui.px(18), ui.px(24), ui.px(12));
            panel.setBackgroundDrawable(ui.glass(0xf52c3d49, 24));
            panel.setFocusableInTouchMode(true);
            LinearLayout heading = ui.horizontal();
            heading.setGravity(Gravity.CENTER_VERTICAL);
            ProgressBar spinner = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
            spinner.setIndeterminate(true);
            if (android.os.Build.VERSION.SDK_INT >= 16) {
                spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
            int size = ui.px(26);
            heading.addView(spinner, new LinearLayout.LayoutParams(size, size));
            TextView caption = ui.text(title, 24, BoxUi.TEXT);
            caption.setPadding(ui.px(14), 0, 0, 0);
            heading.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
            panel.addView(heading, new LinearLayout.LayoutParams(-1, ui.px(52)));

            TextView cancel = ui.text("取消加载 · 返回键", 18, BoxUi.SUB);
            cancel.setGravity(Gravity.CENTER);
            cancel.setPadding(ui.px(14), 0, ui.px(14), 0);
            cancel.setBackgroundDrawable(ui.capsuleGlass(0x555d7586));
            cancel.setContentDescription("取消加载，遥控器可按返回键");
            // Focus stays on the panel so a repeated OK cannot cancel or restart the request.
            cancel.setFocusable(false);
            cancel.setOnClickListener(view -> {
                if (loadingDialog == dialog && pending != null) cancelPending();
            });
            LinearLayout.LayoutParams cancelBounds = new LinearLayout.LayoutParams(-2, ui.px(42));
            cancelBounds.gravity = Gravity.RIGHT;
            panel.addView(cancel, cancelBounds);
            dialog.setContentView(panel);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(0));
                window.setDimAmount(0.4f);
                window.setWindowAnimations(0);
            }
            try {
                dialog.show();
                loadingDialog = dialog;
                if (window != null) {
                    int availableWidth = context.getResources().getDisplayMetrics().widthPixels - ui.px(32);
                    window.setLayout(Math.min(ui.px(396), availableWidth), -2);
                }
                panel.requestFocus();
                return;
            } catch (WindowManager.BadTokenException detached) {
                // The host may be finishing while a configuration request is starting.
            }
        }
        statusBeforeInlineLoading = status.getText().toString();
        message(title);
    }

    private void finishLoading() {
        pending = null;
        Dialog dialog = loadingDialog;
        loadingDialog = null;
        if (dialog != null) dialog.dismiss();
        if (statusBeforeInlineLoading != null) {
            message(statusBeforeInlineLoading);
            statusBeforeInlineLoading = null;
        }
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
        return artwork(site, item, "vod_pic");
    }

    private List<String> homeArtwork(BoxSource.Site site, JSONObject item) {
        Set<String> pictures = new LinkedHashSet<>();
        for (String key : new String[]{"vod_backdrop", "vod_pic_slide", "vod_fanart",
                "backdrop", "vod_banner", "vod_pic_bg", "vod_pic_thumb", "vod_thumb"}) {
            String picture = artwork(site, item, key);
            if (!picture.isEmpty()) pictures.add(picture);
            if (pictures.size() == 3) break;
        }
        // Verified WeTV artwork for the 2026 film, whose feed only exposes a portrait.
        if (isTaisui(item)) {
            pictures.add("https://newpic.wetvinfo.com/vpic_cover/z41029wg748/z41029wg748_hz.jpg/1280");
        }
        String poster = artwork(site, item);
        if (!poster.isEmpty()) pictures.add(poster);
        return new ArrayList<>(pictures);
    }

    private static boolean isTaisui(JSONObject item) {
        return "太岁".equals(videoName(item)) && "2026".equals(item.optString("vod_year").trim());
    }

    private String artwork(BoxSource.Site site, JSONObject item, String... keys) {
        for (String key : keys) {
            String picture = item.optString(key).trim();
            if (picture.isEmpty() || picture.equalsIgnoreCase("null")) continue;
            try {
                int offset = BoxArt.headerOffset(picture);
                String suffix = offset < 0 ? "" : picture.substring(offset);
                if (offset >= 0) picture = picture.substring(0, offset);
                picture = source.playbackUrl(picture);
                String origin = site.type == 3 ? site.origin : BoxHttp.resolve(site.origin, site.api);
                return BoxHttp.resolve(origin, picture) + suffix;
            } catch (Exception invalid) {
                // Other artwork fields can still contain a usable image.
            }
        }
        return "";
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
            cancelPending();
            return true;
        }
        if (dashPlayer != null) { if (!dashPlayer.hideControls()) closeDashPlayer(); return true; }
        if (history.isEmpty()) return false;
        show(history.pop(), false);
        return true;
    }

    private void cancelPending() {
        generation++;
        pending.cancel(true);
        if (pendingHome != null) {
            pendingHome.cancelRequests();
            pendingHome = null;
            homeLoads.purge();
        } else {
            browseHttp.cancelRequests();
        }
        finishLoading();
    }

    @Override public void setActive(boolean active) {
        if (!active) cancelHomePreview();
        if (dashPlayer != null) dashPlayer.setActive(active);
        else if(active)main.post(()->{
            if (!disposed && root.isShown()) {
                if (homePage != null) homePage.restartRotation();
                if (root.findFocus() == null) focusContent();
            }
        });
    }
    @Override public void update(long positionMs, boolean playing) { }

    @Override public void dispose() {
        if (disposed) return;
        disposed = true;
        if (homeMenu != null) homeMenu.dismiss();
        if (episodeDialog != null) episodeDialog.dismiss();
        if (screen != null) cancelSearch(screen.search);
        searches.shutdownNow();
        if (authorizationPreferences != null && authorizationListener != null)
            authorizationPreferences.unregisterOnSharedPreferenceChangeListener(authorizationListener);
        closeDashPlayer();
        if (sniffer != null) sniffer.close();
        cancelHomePreview();
        cancelRecommendations();recommendations.shutdownNow();homes.clear();pages.clear();
        if (pending != null) cancelPending();
        homeLoads.shutdownNow();
        if (art != null) art.close();
        if (ui != null) ui.close();
        main.removeCallbacksAndMessages(null);
        worker.execute(() -> { if (source != null) source.close(); });
        worker.shutdown();
    }
}
