package xiao.bu.channel.files;

import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import xiao.bu.tv.jar.ChannelPlugin;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** One native View: sources, directory browser and video playback. */
public final class FileBrowserPlugin implements ChannelPlugin, ChannelPlugin.NativePage {
    private Context context;
    private DirectoryHost host;
    private FrameLayout root, playback;
    private LinearLayout browser, form;
    private LinearLayout discovery;
    private View discoveryShell;
    private LanScanner scanner;
    private TextView status, path;
    private GridView list;
    private int storageTab;
    private Button[] browserTabs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(2));
    private Future<?> pending;
    private final ArrayList<BrowserEntry> entries = new ArrayList<>();
    private final ArrayDeque<BrowserEntry> stack = new ArrayDeque<>();
    private BrowserEntry current;
    private SmbSession smb;
    private MediaProxy proxy;
    private VideoView video;
    private VideoControlsOverlay controller;
    private boolean mediaReady,foreground=true;
    private String playbackTitle="视频";
    private int generation;
    private boolean disposed, resume;
    private boolean storagePermissionPending;
    private boolean storagePermissionNeeded;
    private interface Task { List<BrowserEntry> run() throws Exception; }

    public View create(Context context, ClassLoader resources, JSONObject config, Host host) {
        if (!(host instanceof DirectoryHost)) throw new IllegalArgumentException("请更新 nTv 宿主");
        this.context = context; this.host = (DirectoryHost)host;
        root = new FileUi.Surface(context) {
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) return onBack();
                    return current != null || form != null || discovery != null || video != null;
                }
                if (video != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    int key = event.getKeyCode();
                    if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        if (mediaReady) {if (video.isPlaying()) video.pause(); else video.start();} controller.show(); return true;
                    }
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if(mediaReady)controller.step(key == KeyEvent.KEYCODE_DPAD_LEFT ? -10000 : 10000);
                        controller.show(); return true;
                    }
                }
                return super.dispatchKeyEvent(event);
            }
        };
        browser = column();browser.setPadding(dp(12),0,0,0);root.addView(withSidebar(browser,0,true));
        LinearLayout heading = new LinearLayout(context); heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = column(); titles.addView(label("媒体库",25));
        heading.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout actions = new LinearLayout(context);
        button(actions, "选择视频", () -> this.host.chooseFiles(files -> {
            if (!disposed && files != null && files.length > 0) play(files[0]);
        }, "video/*", false));
        if (Build.VERSION.SDK_INT >= 21) button(actions, "选择目录", () -> this.host.chooseDirectory(files -> {
            if (!disposed && files != null && files.length > 0) {
                stack.clear(); navigate(new BrowserEntry("授权目录", files[0].toString(), true, 1), false);
            } else if (!disposed) status.setText("未选择目录，或设备没有系统文件选择器；可使用本机 / USB 入口");
        }));
        button(actions, "上一级", () -> onBack());
        HorizontalScrollView actionScroll = new HorizontalScrollView(context); actionScroll.setHorizontalScrollBarEnabled(false); actionScroll.addView(actions);
        heading.addView(actionScroll,new LinearLayout.LayoutParams(dp(156),-2));browser.addView(heading);
        path = label("", 11);path.setSingleLine(true);path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);browser.addView(path);
        list = grid();
        list.setAdapter(new ArrayAdapter<BrowserEntry>(context, android.R.layout.simple_list_item_1, entries) {
            public View getView(int position, View convert, ViewGroup parent) {
                BrowserEntry entry=getItem(position);FileUi.Card view=convert instanceof FileUi.Card?(FileUi.Card)convert:new FileUi.Card(context);
                int kind=entry.directory?(current==null&&(storageTab==1||"本机存储".equals(entry.name))?1:0):3;
                view.bind(entry.name,entry.directory?(current==null?"存储空间 · 点击浏览":"文件夹 · 打开查看"):"视频 · 即点即播",kind);
                view.setLayoutParams(new AbsListView.LayoutParams(-1,dp(140)));return view;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            BrowserEntry entry = entries.get(position);
            if (entry.directory) navigate(entry, true);
            else if (entry.kind == 2) playSmb(entry);
            else play(entry.kind == 1 ? Uri.parse(entry.location) : Uri.fromFile(new File(entry.location)));
        });
        browser.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        status = label("", 11); browser.addView(status);
        home(); return root;
    }
    private void home() {
        stack.clear(); current = null; path.setText(storageTab==0?"资料库  /  本机存储":"设备  /  U 盘与移动硬盘");
        final int selectedTab=storageTab;
        load(() -> {
            LinkedHashSet<String> roots = new LinkedHashSet<>();
            File primary=Environment.getExternalStorageDirectory();
            if(selectedTab==0){
                List<BrowserEntry> local=new ArrayList<>();local.add(new BrowserEntry("本机存储",primary.getAbsolutePath(),true,0));
                for(String name:new String[]{"Movies","Download"}){File dir=new File(primary,name);if(dir.isDirectory())local.add(new BrowserEntry(name.equals("Movies")?"影片":"下载",dir.getAbsolutePath(),true,0));}
                return local;
            }
            File[] external = Build.VERSION.SDK_INT >= 19 ? ModernStorage.externalDirs(context) : new File[]{context.getExternalFilesDir(null)};
            if (external != null) for (File file : external) if (file != null) {
                String value = file.getAbsolutePath(); int split = value.indexOf("/Android/");
                if (split > 0) roots.add(value.substring(0, split));
            }
            for (String parent : new String[]{"/storage", "/mnt", "/mnt/usb_storage", "/mnt/media_rw"}) {
                File[] mounts = new File(parent).listFiles();
                if (mounts != null) for (File mount : mounts) if (mount.isDirectory() && mount.canRead()
                        && !Arrays.asList("emulated","self","asec","obb","secure","shell","media_rw","runtime","user","mnt").contains(mount.getName())) roots.add(mount.getAbsolutePath());
            }
            List<BrowserEntry> result = new ArrayList<>();
            LinkedHashSet<String> seen=new LinkedHashSet<>();
            for (String value : roots) {File mount=new File(value);String canonical=mount.getCanonicalPath();
                if(canonical.equals(primary.getCanonicalPath())||!seen.add(canonical)||!mount.canRead())continue;
                File[] children=mount.listFiles();if(children==null||children.length==0)continue;
                result.add(new BrowserEntry("外接存储 · " + mount.getName(),value,true,0));}
            return result;
        });
    }
    private void navigate(BrowserEntry folder, boolean push) {
        if (push && current != null) stack.push(current);
        current = folder; path.setText("资料库  /  "+folder.name+"  ·  "+folder.location);
        if (folder.kind == 0 && !ensureStoragePermission()) return;
        final SmbSession session = smb;
        load(() -> {
            if (folder.kind == 2) return session.list(folder.location);
            if (folder.kind == 1 && Build.VERSION.SDK_INT >= 21) return ModernStorage.documents(context, Uri.parse(folder.location));
            File[] files = new File(folder.location).listFiles();
            if (files == null) throw new IOException("目录不可读");
            List<BrowserEntry> result = new ArrayList<>();
            for (File file : files) if (file.isDirectory() || BrowserEntry.video(file.getName()))
                result.add(new BrowserEntry(file.getName(), file.getAbsolutePath(), file.isDirectory(), 0));
            return result;
        });
    }
    private boolean ensureStoragePermission() {
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT > 32
                || ModernStorage.canReadSharedStorage(context)) return true;
        generation++;
        storagePermissionNeeded = true;
        if (pending != null) pending.cancel(true);
        entries.clear(); changed();
        status.setText("请允许读取本机视频；也可使用“选择目录”授权访问");
        if (!storagePermissionPending && context instanceof android.app.Activity) {
            storagePermissionPending = true;
            final long deadline = SystemClock.uptimeMillis() + 30000;
            ModernStorage.requestSharedStorage((android.app.Activity)context);
            main.postDelayed(new Runnable() {
                boolean dialogSeen;
                public void run() {
                    if (disposed || !storagePermissionPending) return;
                    if (ModernStorage.canReadSharedStorage(context)) {
                        storagePermissionPending = false;
                        storagePermissionNeeded = false;
                        if (current != null && current.kind == 0) navigate(current, false);
                        return;
                    }
                    if (!root.hasWindowFocus()) dialogSeen = true;
                    if (SystemClock.uptimeMillis() >= deadline || dialogSeen && root.hasWindowFocus()) {
                        storagePermissionPending = false;
                        status.setText("未获得读取权限，请点击刷新重试，或使用“选择目录”");
                        return;
                    }
                    main.postDelayed(this, 500);
                }
            }, 500);
        }
        return false;
    }
    private void load(Task task) {
        int token = ++generation;final boolean preserveOrder=current==null&&storageTab==0; entries.clear(); changed(); status.setText("正在读取…");
        if (pending != null) pending.cancel(true); worker.purge();
        pending = worker.submit(() -> {
            try {
                List<BrowserEntry> result = task.run(); if(!preserveOrder)Collections.sort(result);
                main.post(() -> { if (!disposed && token == generation) {
                    entries.addAll(result); changed(); status.setText(result.isEmpty() ? (current==null&&storageTab==1?"尚未发现外接存储 · 插入设备后点击刷新":"此目录没有视频或子文件夹") : result.size() + " 项 · 方向键选择，确定打开");
                    list.setSelection(0); list.requestFocus();
                }});
            } catch (Exception | LinkageError error) { main.post(() -> { if (!disposed && token == generation)
                status.setText("读取失败：请检查设备连接、共享名、账号和访问权限；本机目录可用“选择目录”授权后重试"); }); }
        });
    }
    private void changed() { ((ArrayAdapter<?>)list.getAdapter()).notifyDataSetChanged(); }
    private void discover() {
        closeDiscovery();
        discovery = column(); final LinearLayout page = discovery;
        page.setPadding(dp(12),0,0,0);page.addView(label("网络共享",25));
        TextView progress = label("正在扫描当前局域网…", 11);progress.setTag("scan-status"); page.addView(progress);
        LinearLayout actions = new LinearLayout(context);
        button(actions, "手动连接", () -> { closeDiscovery(); connectForm(null, false); });
        button(actions, "重新扫描", () -> discover());
        button(actions, "返回", () -> closeDiscovery()); page.addView(actions);
        ArrayList<String> hosts = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, hosts) {
            public View getView(int position, View convert, ViewGroup parent) {
                FileUi.Card view=convert instanceof FileUi.Card?(FileUi.Card)convert:new FileUi.Card(context);
                view.bind(getItem(position),"SMB 共享 · 免密优先",2);view.setLayoutParams(new AbsListView.LayoutParams(-1,dp(140)));return view;
            }
        };
        GridView devices = grid();devices.setTag("scan-devices"); devices.setAdapter(adapter);
        devices.setOnItemClickListener((parent, view, position, id) -> {
            String address = "smb://" + hosts.get(position) + "/";
            closeDiscovery(); connectForm(address, true);
        });
        page.addView(devices, new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(label("选择设备即可尝试免密访问  ·  也支持手动连接", 11));
        FileUi.Surface surface=new FileUi.Surface(context);discoveryShell=surface;surface.addView(withSidebar(page,2,false));root.addView(surface,new FrameLayout.LayoutParams(-1,-1));devices.requestFocus();
        scanner = new LanScanner(Build.VERSION.SDK_INT<=15?8:16); scanner.start(new LanScanner.Listener() {
            public void found(String address) { main.post(() -> {
                if (disposed || discovery != page || hosts.contains(address)) return;
                hosts.add(address); Collections.sort(hosts); adapter.notifyDataSetChanged();
            }); }
            public void progress(int completed, int total) { if (completed % 12 == 0 || completed == total) main.post(() -> {
                if (!disposed && discovery == page) progress.setText("正在扫描 " + completed + "/" + total + " · 已发现 " + hosts.size() + " 台");
            }); }
            public void finished(boolean limited) { main.post(() -> {
                if (!disposed && discovery == page) progress.setText((hosts.isEmpty() ? "未发现设备，可重新扫描或手动连接" : "扫描完成 · 发现 " + hosts.size() + " 台")
                        + (limited ? "（较大网络仅扫描本机附近 254 个地址）" : ""));
            }); }
        });
    }
    private void closeDiscovery() {
        if (scanner != null) { scanner.close(); scanner = null; }
        if (discovery != null) { root.removeView(discoveryShell);discoveryShell=null; discovery = null; list.requestFocus(); }
    }
    private void connectForm(String initialAddress, boolean automatic) {
        if (form != null) return;
        form = column(); form.setPadding(dp(20), dp(16), dp(20), dp(16));form.setBackgroundDrawable(FileUi.plate(0xee35547b,0xee152944,dp(16),0x669dc8ef));
        form.addView(label("共享资料库",10));form.addView(label("连接服务器",24));
        SharedPreferences prefs = context.getSharedPreferences("file-browser", 0);
        EditText address = field("smb://192.168.1.10/视频/", initialAddress == null ? prefs.getString("address", "") : initialAddress, false);
        EditText user = field("用户名（免密共享可留空）", automatic ? "" : prefs.getString("user", ""), false);
        EditText password = field("密码（仅本次会话）", "", true);
        EditText domain = field("域（可留空）", automatic ? "" : prefs.getString("domain", ""), false);
        TextView connectionStatus = label(automatic ? "正在尝试免密访问…" : "免密共享只需填写地址，账号和密码留空", 11); form.addView(connectionStatus);
        button(form, "连接", () -> {
            final String url = address.getText().toString().trim(), username = user.getText().toString().trim(),
                    secret = password.getText().toString(), realm = domain.getText().toString().trim();
            // BaseContext can resolve local/NetBIOS addresses during initialization.
            final int token = ++generation;
            if (pending != null) pending.cancel(true); worker.purge();
            status.setText("正在连接 SMB…");
            connectionStatus.setText("正在连接 SMB…");
            pending = worker.submit(() -> {
                SmbSession candidate = null;
                try {
                    candidate = new SmbSession(url, realm, username, secret);
                    final SmbSession next = candidate;
                    final List<BrowserEntry> result = next.list(next.root); Collections.sort(result);
                    main.post(() -> {
                        if (disposed || token != generation || form == null) { cleanup(next); return; }
                        SmbSession previous = smb; smb = next;
                        if (previous != null) cleanup(previous);
                        prefs.edit().putString("address", url).putString("user", username).putString("domain", realm).apply();
                        password.setText(""); closeForm(); stack.clear();
                        current = new BrowserEntry("SMB", next.root, true, 2); path.setText(next.root);
                        entries.clear(); entries.addAll(result); changed(); list.setSelection(0); list.requestFocus();
                        status.setText(result.isEmpty() ? "此目录没有视频或子文件夹" : result.size() + " 项 · 确定打开，返回上一级");
                    });
                } catch (Exception | LinkageError error) {
                    if (candidate != null) candidate.close();
                    main.post(() -> { if (!disposed && token == generation) {
                        status.setText("SMB 连接失败，请检查地址、账号和共享权限");
                        connectionStatus.setText("免密访问不可用或连接失败；请填写共享路径或账号密码后重试");
                        Toast.makeText(context, "SMB 连接失败，请检查地址、账号和共享权限", Toast.LENGTH_LONG).show();
                    }});
                }
            });
        });
        button(form, "取消", () -> closeForm());
        ScrollView scroll = new ScrollView(context);scroll.setFillViewport(false);scroll.setPadding(dp(28),dp(14),dp(28),dp(14));scroll.setBackgroundColor(0xcc0c1c34); scroll.setTag("connection"); scroll.addView(form);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1)); address.requestFocus();
        if (automatic) { Button connect = null; for (int i = 0; i < form.getChildCount(); i++) {
            View child = form.getChildAt(i); if (child instanceof Button && "连接".contentEquals(((Button)child).getText())) connect = (Button)child;
        } if (connect != null) connect.performClick(); }
    }
    private EditText field(String hint, String value, boolean password) {
        EditText input = new EditText(context); input.setSingleLine(true); input.setTextColor(Color.WHITE);
        input.setTextColor(FileUi.INK);input.setTextSize(14);input.setHintTextColor(FileUi.MUTED); input.setHint(hint); input.setText(value);
        input.setBackgroundDrawable(FileUi.plate(0x9912223b,0x99415e7e,dp(8),0x777ba2ca));input.setPadding(dp(12),dp(9),dp(12),dp(9));
        input.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(40));params.bottomMargin=dp(9);form.addView(input,params); return input;
    }
    private void closeForm() { generation++; if (pending != null) pending.cancel(true); View modal = root.findViewWithTag("connection"); if (modal != null) root.removeView(modal); form = null; list.requestFocus(); }
    private void playSmb(BrowserEntry entry) {
        playbackTitle=entry.name;
        try { stopVideo(); proxy = new MediaProxy(smb.source(entry.location)); startVideo(Uri.parse(proxy.url())); }
        catch (IOException error) { status.setText("无法启动 SMB 视频读取，请重试"); }
    }
    private void play(Uri uri) { playbackTitle=uri.getLastPathSegment()==null?"视频":uri.getLastPathSegment();stopVideo(); startVideo(uri); }
    private void startVideo(Uri uri) {
        playback = new FrameLayout(context); playback.setBackgroundColor(Color.BLACK);
        video = new VideoView(context); FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        playback.addView(video, params); root.addView(playback, new FrameLayout.LayoutParams(-1, -1));
        mediaReady=false;
        controller=new VideoControlsOverlay((android.app.Activity)context,new VideoControlsOverlay.Playback(){
            public boolean playing(){return mediaReady&&video!=null&&video.isPlaying();}
            public boolean completed(){return false;}
            public String status(){return mediaReady?"":"正在加载视频";}
            public boolean loading(){return !mediaReady;}
            public long duration(){return mediaReady&&video!=null?video.getDuration():0;}
            public long position(){return mediaReady&&video!=null?video.getCurrentPosition():0;}
            public void toggle(){if(mediaReady&&video!=null){if(video.isPlaying())video.pause();else video.start();}}
            public void seek(long position){if(mediaReady&&video!=null)video.seekTo((int)Math.max(0,Math.min(video.getDuration()-1,position)));}
            public String title(){return playbackTitle;}
            public boolean speedSupported(){return false;}
            public float speed(){return 1f;}
            public void speed(float value){}
            public boolean catchup(){return false;}
            public void live(){}
        },context.getPackageManager().hasSystemFeature("android.hardware.type.television"));
        playback.addView(controller,new FrameLayout.LayoutParams(-1,-1));controller.show();
        video.setOnTouchListener((view,event)->{if(event.getActionMasked()==MotionEvent.ACTION_UP&&controller!=null)controller.show();return true;});
        video.setOnPreparedListener(player -> { if (video != null) { mediaReady=true;if(foreground){video.start();controller.show();}else{resume=true;controller.hide();} } });
        video.setOnErrorListener((player, what, extra) -> { stopVideo(); status.setText("播放失败：请检查文件权限、SMB 连接及设备是否支持此视频编码"); return true; });
        video.setOnCompletionListener(player -> stopVideo());
        video.setVideoURI(uri); video.requestFocus();
    }
    private void stopVideo() {
        resume = false;mediaReady=false;
        if (controller != null) { controller.hide(); controller = null; }
        if (video != null) { video.stopPlayback(); video = null; }
        if (playback != null) { root.removeView(playback); playback = null; }
        if (proxy != null) { proxy.close(); proxy = null; }
        if (list != null) list.requestFocus();
    }
    public boolean onBack() {
        if (video != null) { stopVideo(); return true; }
        if (discovery != null) { closeDiscovery(); return true; }
        if (form != null) { generation++; if (pending != null) pending.cancel(true); closeForm(); return true; }
        if (!stack.isEmpty()) { navigate(stack.pop(), false); return true; }
        if (current != null) { home(); return true; }
        return false;
    }
    public void setActive(boolean active) {
        foreground=active;
        if (active && storagePermissionNeeded && Build.VERSION.SDK_INT >= 23
                && ModernStorage.canReadSharedStorage(context)) {
            storagePermissionNeeded = storagePermissionPending = false;
            if (current != null && current.kind == 0) navigate(current, false);
        }
        if (video != null) {
            if (!active) { resume = video.isPlaying(); video.pause();if(controller!=null)controller.hide(); }
            else if (resume) { video.start(); resume = false; }
        }
    }
    public void update(long positionMs, boolean playing) {}
    private void cleanup(SmbSession session) { new Thread(() -> session.close(), "files-smb-close").start(); }
    public void dispose() {
        disposed = true; generation++; closeDiscovery(); stopVideo(); worker.shutdownNow(); main.removeCallbacksAndMessages(null);
        if (smb != null) { cleanup(smb); smb = null; }
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView label(String text, int size) { TextView view = new TextView(context); view.setText(text); view.setTextSize(size);view.setTextColor(size<=11?FileUi.MUTED:FileUi.INK);view.setTypeface(android.graphics.Typeface.create("sans-serif",size>=22?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL)); view.setPadding(0, dp(3), 0, dp(3)); return view; }
    private void button(LinearLayout parent, String title, Runnable action) { Button button = new Button(context); button.setText(title);FileUi.button(button,false); button.setOnClickListener(v -> action.run());LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(parent.getOrientation()==LinearLayout.VERTICAL?-1:-2,dp(32));params.rightMargin=dp(6);params.bottomMargin=dp(5);parent.addView(button,params); }
    private GridView grid(){GridView grid=new GridView(context);grid.setNumColumns(GridView.AUTO_FIT);grid.setColumnWidth(dp(120));grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setHorizontalSpacing(dp(8));grid.setVerticalSpacing(dp(6));grid.setPadding(0,dp(2),0,dp(2));grid.setClipToPadding(false);grid.setCacheColorHint(Color.TRANSPARENT);grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setOnFocusChangeListener((v,focused)->{for(int i=0;i<grid.getChildCount();i++)grid.getChildAt(i).invalidate();});return grid;}
    private View tabs(int active,boolean remember){
        LinearLayout strip=column();strip.setPadding(dp(9),dp(12),dp(9),dp(10));strip.setBackgroundDrawable(FileUi.plate(0x885575a2,0x553f597e,dp(18),0x8891b8e8));
        LinearLayout brand=new LinearLayout(context);brand.setGravity(Gravity.CENTER_VERTICAL);brand.addView(FileUi.appIcon(context),new LinearLayout.LayoutParams(dp(32),dp(32)));TextView name=label("文件",21);LinearLayout.LayoutParams word=new LinearLayout.LayoutParams(-2,-2);word.leftMargin=dp(9);brand.addView(name,word);strip.addView(brand);strip.addView(label("位置",11));
        Button[] choices=new Button[3];String[] titles={"本机","外接存储","SMB"};
        for(int i=0;i<3;i++){final int target=i;Button tab=new Button(context);tab.setText(titles[i]);FileUi.button(tab,i==active);android.graphics.drawable.BitmapDrawable icon=new android.graphics.drawable.BitmapDrawable(context.getResources(),FileUi.glyph(i==2?2:1));icon.setBounds(0,0,dp(21),dp(20));tab.setCompoundDrawables(icon,null,null,null);tab.setCompoundDrawablePadding(dp(6));tab.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(35));params.bottomMargin=dp(5);strip.addView(tab,params);choices[i]=tab;
            tab.setOnClickListener(v->{if(form!=null)closeForm();if(target==2){discover();return;}closeDiscovery();storageTab=target;for(int j=0;j<3;j++)FileUi.button(browserTabs[j],j==target);home();});}
        strip.addView(new View(context),new LinearLayout.LayoutParams(1,0,1));button(strip,"刷新",()->{if(discovery!=null)discover();else if(current==null)home();else navigate(current,false);});strip.addView(label("方向键选择\n确定打开\n返回上一级",10));if(remember)browserTabs=choices;return strip;
    }
    private View withSidebar(LinearLayout content,int active,boolean remember){LinearLayout shell=new LinearLayout(context);shell.setPadding(dp(12),dp(12),dp(12),dp(10));int width=Math.min(dp(166),Math.max(dp(112),context.getResources().getDisplayMetrics().widthPixels/5));shell.addView(tabs(active,remember),new LinearLayout.LayoutParams(width,-1));shell.addView(content,new LinearLayout.LayoutParams(0,-1,1));return shell;}
}
