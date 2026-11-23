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
    private LanScanner scanner;
    private TextView status, path;
    private ListView list;
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
    private MediaController controller;
    private int generation;
    private boolean disposed, resume;
    private interface Task { List<BrowserEntry> run() throws Exception; }

    public View create(Context context, ClassLoader resources, JSONObject config, Host host) {
        if (!(host instanceof DirectoryHost)) throw new IllegalArgumentException("请更新 nTv 宿主");
        this.context = context; this.host = (DirectoryHost)host;
        root = new FrameLayout(context) {
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) return onBack();
                    return current != null || form != null || discovery != null || video != null;
                }
                if (video != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    int key = event.getKeyCode();
                    if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        if (video.isPlaying()) video.pause(); else video.start(); controller.show(5000); return true;
                    }
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        video.seekTo(Math.max(0, Math.min(video.getDuration(), video.getCurrentPosition() + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -10000 : 10000))));
                        controller.show(5000); return true;
                    }
                }
                return super.dispatchKeyEvent(event);
            }
        };
        root.setBackgroundColor(Color.rgb(16, 23, 36));
        browser = column(); browser.setPadding(dp(20), dp(12), dp(20), dp(12)); root.addView(browser);
        TextView title = label("文件浏览", 24); browser.addView(title);
        LinearLayout actions = new LinearLayout(context);
        button(actions, "本机 / USB", () -> home());
        button(actions, "选择视频", () -> this.host.chooseFiles(files -> {
            if (!disposed && files != null && files.length > 0) play(files[0]);
        }, "video/*", false));
        if (Build.VERSION.SDK_INT >= 21) button(actions, "选择目录", () -> this.host.chooseDirectory(files -> {
            if (!disposed && files != null && files.length > 0) {
                stack.clear(); navigate(new BrowserEntry("授权目录", files[0].toString(), true, 1), false);
            } else if (!disposed) status.setText("未选择目录，或设备没有系统文件选择器；可使用本机 / USB 入口");
        }));
        button(actions, "SMB", () -> discover());
        button(actions, "上一级", () -> onBack());
        button(actions, "刷新", () -> { if (current == null) home(); else navigate(current, false); });
        HorizontalScrollView actionScroll = new HorizontalScrollView(context); actionScroll.addView(actions); browser.addView(actionScroll);
        path = label("", 16); browser.addView(path);
        list = new ListView(context); list.setCacheColorHint(Color.TRANSPARENT);
        list.setAdapter(new ArrayAdapter<BrowserEntry>(context, android.R.layout.simple_list_item_1, entries) {
            public View getView(int position, View convert, ViewGroup parent) {
                TextView view = (TextView)super.getView(position, convert, parent);
                view.setTextColor(Color.WHITE); view.setTextSize(19); view.setMinHeight(dp(52)); return view;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            BrowserEntry entry = entries.get(position);
            if (entry.directory) navigate(entry, true);
            else if (entry.kind == 2) playSmb(entry);
            else play(entry.kind == 1 ? Uri.parse(entry.location) : Uri.fromFile(new File(entry.location)));
        });
        browser.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        status = label("", 15); browser.addView(status);
        home(); return root;
    }
    private void home() {
        stack.clear(); current = null; path.setText(Build.VERSION.SDK_INT >= 21
                ? "本机与外接存储 · 选择目录可授权访问 USB / 移动硬盘"
                : "本机与外接存储 · USB / 移动硬盘需先由系统挂载，可插入后刷新");
        load(() -> {
            LinkedHashSet<String> roots = new LinkedHashSet<>();
            roots.add(Environment.getExternalStorageDirectory().getAbsolutePath());
            File[] external = Build.VERSION.SDK_INT >= 19 ? ModernStorage.externalDirs(context) : new File[]{context.getExternalFilesDir(null)};
            if (external != null) for (File file : external) if (file != null) {
                String value = file.getAbsolutePath(); int split = value.indexOf("/Android/");
                if (split > 0) roots.add(value.substring(0, split));
            }
            for (String parent : new String[]{"/storage", "/mnt", "/mnt/usb_storage", "/mnt/media_rw"}) {
                File[] mounts = new File(parent).listFiles();
                if (mounts != null) for (File mount : mounts) if (mount.isDirectory() && mount.canRead()
                        && !mount.getName().equals("emulated") && !mount.getName().equals("self")) roots.add(mount.getAbsolutePath());
            }
            List<BrowserEntry> result = new ArrayList<>();
            for (String value : roots) result.add(new BrowserEntry(value.equals(Environment.getExternalStorageDirectory().getAbsolutePath())
                    ? "本机存储" : "存储设备 " + new File(value).getName(), value, true, 0));
            return result;
        });
    }
    private void navigate(BrowserEntry folder, boolean push) {
        if (push && current != null) stack.push(current);
        current = folder; path.setText(folder.location);
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
    private void load(Task task) {
        int token = ++generation; entries.clear(); changed(); status.setText("正在读取…");
        if (pending != null) pending.cancel(true); worker.purge();
        pending = worker.submit(() -> {
            try {
                List<BrowserEntry> result = task.run(); Collections.sort(result);
                main.post(() -> { if (!disposed && token == generation) {
                    entries.addAll(result); changed(); status.setText(result.isEmpty() ? "此目录没有视频或子文件夹" : result.size() + " 项 · 确定打开，返回上一级");
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
        page.setPadding(dp(24), dp(12), dp(24), dp(12)); page.setBackgroundColor(Color.rgb(24, 33, 49));
        page.addView(label("局域网 SMB 设备", 23));
        TextView progress = label("正在扫描当前局域网…", 16); page.addView(progress);
        LinearLayout actions = new LinearLayout(context);
        button(actions, "手动连接", () -> { closeDiscovery(); connectForm(null, false); });
        button(actions, "重新扫描", () -> discover());
        button(actions, "返回", () -> closeDiscovery()); page.addView(actions);
        ArrayList<String> hosts = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, hosts) {
            public View getView(int position, View convert, ViewGroup parent) {
                TextView view = (TextView)super.getView(position, convert, parent); view.setTextColor(Color.WHITE);
                view.setText("SMB  ·  " + getItem(position)); view.setTextSize(20); return view;
            }
        };
        ListView devices = new ListView(context); devices.setAdapter(adapter);
        devices.setOnItemClickListener((parent, view, position, id) -> {
            String address = "smb://" + hosts.get(position) + "/";
            closeDiscovery(); connectForm(address, true);
        });
        page.addView(devices, new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(label("选择设备后自动尝试免密访问；也可手动填写共享地址", 16));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1)); devices.requestFocus();
        scanner = new LanScanner(); scanner.start(new LanScanner.Listener() {
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
        if (discovery != null) { root.removeView(discovery); discovery = null; list.requestFocus(); }
    }
    private void connectForm(String initialAddress, boolean automatic) {
        if (form != null) return;
        form = column(); form.setPadding(dp(28), dp(12), dp(28), dp(12)); form.setBackgroundColor(Color.rgb(24, 33, 49));
        form.addView(label("连接局域网 SMB 2 / 3", 22));
        SharedPreferences prefs = context.getSharedPreferences("file-browser", 0);
        EditText address = field("smb://192.168.1.10/视频/", initialAddress == null ? prefs.getString("address", "") : initialAddress, false);
        EditText user = field("用户名（免密共享可留空）", automatic ? "" : prefs.getString("user", ""), false);
        EditText password = field("密码（仅本次会话）", "", true);
        EditText domain = field("域（可留空）", automatic ? "" : prefs.getString("domain", ""), false);
        TextView connectionStatus = label(automatic ? "正在尝试免密访问…" : "免密共享只需填写地址，账号和密码留空", 16); form.addView(connectionStatus);
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
        ScrollView scroll = new ScrollView(context); scroll.setTag("connection"); scroll.addView(form);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1)); address.requestFocus();
        if (automatic) { Button connect = null; for (int i = 0; i < form.getChildCount(); i++) {
            View child = form.getChildAt(i); if (child instanceof Button && "连接".contentEquals(((Button)child).getText())) connect = (Button)child;
        } if (connect != null) connect.performClick(); }
    }
    private EditText field(String hint, String value, boolean password) {
        EditText input = new EditText(context); input.setSingleLine(true); input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xffaab7ca); input.setHint(hint); input.setText(value);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        form.addView(input); return input;
    }
    private void closeForm() { generation++; if (pending != null) pending.cancel(true); View modal = root.findViewWithTag("connection"); if (modal != null) root.removeView(modal); form = null; list.requestFocus(); }
    private void playSmb(BrowserEntry entry) {
        try { stopVideo(); proxy = new MediaProxy(smb.source(entry.location)); startVideo(Uri.parse(proxy.url())); }
        catch (IOException error) { status.setText("无法启动 SMB 视频读取，请重试"); }
    }
    private void play(Uri uri) { stopVideo(); startVideo(uri); }
    private void startVideo(Uri uri) {
        playback = new FrameLayout(context); playback.setBackgroundColor(Color.BLACK);
        video = new VideoView(context); FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        playback.addView(video, params); root.addView(playback, new FrameLayout.LayoutParams(-1, -1));
        controller = new MediaController(context); controller.setAnchorView(video); video.setMediaController(controller);
        video.setOnPreparedListener(player -> { if (video != null) { video.start(); controller.show(4000); } });
        video.setOnErrorListener((player, what, extra) -> { stopVideo(); status.setText("播放失败：请检查文件权限、SMB 连接及设备是否支持此视频编码"); return true; });
        video.setOnCompletionListener(player -> stopVideo());
        video.setVideoURI(uri); video.requestFocus();
    }
    private void stopVideo() {
        resume = false;
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
        if (video != null) {
            if (!active) { resume = video.isPlaying(); video.pause(); }
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
    private TextView label(String text, int size) { TextView view = new TextView(context); view.setText(text); view.setTextSize(size); view.setTextColor(Color.WHITE); view.setPadding(0, dp(6), 0, dp(6)); return view; }
    private void button(LinearLayout parent, String title, Runnable action) { Button button = new Button(context); button.setText(title); button.setOnClickListener(v -> action.run()); parent.addView(button); }
}
