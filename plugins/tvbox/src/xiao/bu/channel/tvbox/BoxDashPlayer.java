package xiao.bu.channel.tvbox;

import android.net.Uri;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.BaseDataSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.video.VideoListener;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.Call;
import okhttp3.Response;
import org.json.JSONObject;
import xiao.bu.channel.bl.BlUi;
import xiao.bu.tv.jar.ChannelPlugin;

/** Standard MPD playback using BL's ExoPlayer core and the shared nTv HTTP transport. */
final class BoxDashPlayer extends FrameLayout {
    private final SimpleExoPlayer player;
    private final TextureView surface;
    private final ChannelPlugin.PlayerUi controls;
    private final Set<Call> calls = Collections.newSetFromMap(new ConcurrentHashMap<Call, Boolean>());
    private boolean closed, wantPlaying = true;
    private boolean rendered;
    private boolean revealControls, touchMoved;
    private float touchX, touchY;
    private String failure = "";
    private int width, height;

    BoxDashPlayer(BlUi ui, ChannelPlugin.PlayerUiHost host, BoxHttp http, BoxSource source,
                  String url, JSONObject headers, String title) {
        super(ui.context);
        setBackgroundColor(0xff000000);
        setContentDescription("TVBox 播放器");
        setClickable(true);
        setFocusableInTouchMode(true);
        surface = new TextureView(ui.context);
        addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        DefaultLoadControl load = new DefaultLoadControl(new com.google.android.exoplayer2.upstream.DefaultAllocator(true, 64 * 1024),
                2000, 10000, 500, 1000, 8 * 1024 * 1024, false);
        player = ExoPlayerFactory.newSimpleInstance(ui.context, new DefaultRenderersFactory(ui.context).setEnableDecoderFallback(true),
                new DefaultTrackSelector(), load);
        player.setAudioAttributes(new com.google.android.exoplayer2.audio.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.CONTENT_TYPE_MOVIE).build(), true);
        player.setVideoTextureView(surface);
        controls = host.createPlayerUi(new ChannelPlugin.PlayerControls() {
            public boolean playing() { return player.getPlayWhenReady() && player.getPlaybackState() == Player.STATE_READY; }
            public boolean completed() { return player.getPlaybackState() == Player.STATE_ENDED; }
            public boolean loading() { return failure.isEmpty() && (!rendered || player.getPlaybackState() == Player.STATE_BUFFERING); }
            public String status() { return !failure.isEmpty() ? failure : loading() ? "正在加载…" : ""; }
            public String title() { return title; }
            public long duration() { return Math.max(0, player.getDuration()); }
            public long position() { return Math.max(0, player.getCurrentPosition()); }
            public void toggle() { BoxDashPlayer.this.toggle(); }
            public void seek(long position) {
                boolean replay = completed();
                player.seekTo(Math.max(0, position));
                if (replay) { wantPlaying = true; player.setPlayWhenReady(true); }
            }
            public float speed() { return player.getPlaybackParameters().speed; }
            public void speed(float value) { player.setPlaybackParameters(new PlaybackParameters(value)); }
        });
        addView(controls.view(), new FrameLayout.LayoutParams(-1, -1));
        player.addVideoListener(new VideoListener() {
            @Override public void onVideoSizeChanged(int w, int h, int rotation, float ratio) {
                width = Math.round(w * ratio);
                height = h;
                fitVideo();
                describeVideo();
            }
            @Override public void onRenderedFirstFrame() {
                rendered = true;
                describeVideo();
                controls.show();
            }
        });
        player.addListener(new Player.DefaultEventListener() {
            @Override public void onPlayerStateChanged(boolean ready, int state) {
                if (closed) return;
                describeVideo();
                if (state == Player.STATE_ENDED) {
                    wantPlaying = false;
                    controls.show();
                }
            }
            @Override public void onPlayerError(ExoPlaybackException error) {
                if (closed) return;
                android.util.Log.w("TvBoxDash", "Playback failed, type=" + error.type);
                failure = error.type == ExoPlaybackException.TYPE_SOURCE
                        ? "播放线路读取失败，请返回选集重试" : "设备无法解码当前音视频轨，请切换线路";
                controls.show();
            }
        });
        DataSource.Factory transport = () -> new SharedSource(http, source, headers, calls);
        player.prepare(new DashMediaSource.Factory(transport).createMediaSource(Uri.parse(url)));
        player.setPlayWhenReady(true);
        controls.show();
        requestFocus();
    }

    private void toggle() {
        if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
        wantPlaying = !wantPlaying;
        player.setPlayWhenReady(wantPlaying);
    }

    boolean hideControls() {
        if (!controls.visible()) return false;
        controls.hide();
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            revealControls = !controls.visible();
            touchMoved = false;
            touchX = event.getX();
            touchY = event.getY();
            controls.show();
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            touchMoved |= Math.abs(event.getX() - touchX) > slop || Math.abs(event.getY() - touchY) > slop;
        }
        boolean handled = controls.surfaceGesture(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP && revealControls && !touchMoved) controls.show();
        return handled;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_DPAD_CENTER && key != KeyEvent.KEYCODE_ENTER
                && key != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && key != KeyEvent.KEYCODE_DPAD_LEFT
                && key != KeyEvent.KEYCODE_DPAD_RIGHT && key != KeyEvent.KEYCODE_MEDIA_REWIND
                && key != KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) return super.dispatchKeyEvent(event);
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_MEDIA_REWIND || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            try { if (controls.remoteKey(event)) return true; }
            catch (LinkageError olderHost) { /* The optional remote HUD API is absent on older hosts. */ }
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                long duration = player.getDuration();
                boolean backward = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_MEDIA_REWIND;
                if (duration > 0) player.seekTo(Math.max(0, Math.min(duration - 1,
                        player.getCurrentPosition() + (backward ? -5000 : 5000))));
                controls.show();
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (controls.visible() || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) toggle();
            controls.show();
        }
        return true;
    }

    private void describeVideo() {
        if (rendered) surface.setContentDescription("视频画面");
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { fitVideo(); }

    private void fitVideo() {
        if (surface == null || width <= 0 || height <= 0 || getWidth() <= 0 || getHeight() <= 0) return;
        float videoAspect = width / (float) height;
        float viewAspect = getWidth() / (float) getHeight();
        android.graphics.Matrix transform = new android.graphics.Matrix();
        transform.setScale(Math.min(1f, videoAspect / viewAspect), Math.min(1f, viewAspect / videoAspect),
                getWidth() / 2f, getHeight() / 2f);
        surface.setTransform(transform);
    }

    void setActive(boolean active) { if (!closed) player.setPlayWhenReady(active && wantPlaying); }

    void close() {
        if (closed) return;
        closed = true;
        controls.dispose();
        for (Call call : calls) call.cancel();
        player.release();
        calls.clear();
    }

    private static final class SharedSource extends BaseDataSource {
        private final BoxHttp http;
        private final BoxSource source;
        private final JSONObject headers;
        private final Set<Call> calls;
        private Call call;
        private Response response;
        private InputStream input;
        private Uri uri;
        private long remaining;
        private boolean opened;

        SharedSource(BoxHttp http, BoxSource source, JSONObject headers, Set<Call> calls) {
            super(true);
            this.http = http;
            this.source = source;
            this.headers = headers;
            this.calls = calls;
        }

        @Override public long open(DataSpec spec) throws IOException {
            transferInitializing(spec);
            try {
                String address = source.playbackUrl(spec.uri.toString());
                HashMap<String, String> options = new HashMap<>();
                if (spec.position > 0 || spec.length != C.LENGTH_UNSET)
                    options.put("range", "bytes=" + spec.position + "-"
                            + (spec.length == C.LENGTH_UNSET ? "" : spec.position + spec.length - 1));
                call = http.mediaCall(BoxHttp.url(address), headers, options);
                calls.add(call);
                response = call.execute();
                if (!response.isSuccessful() || response.body() == null) throw new IOException("DASH HTTP " + response.code());
                uri = Uri.parse(response.request().url().toString());
                input = response.body().byteStream();
                long skipped = 0;
                if (response.code() == 200 && spec.position > 0) {
                    if (spec.position > 1024 * 1024) throw new IOException("播放线路未支持 Range");
                    while (skipped < spec.position) {
                        long count = input.skip(spec.position - skipped);
                        if (count == 0) {
                            if (input.read() == -1) throw new IOException("DASH Range 超出内容");
                            count = 1;
                        }
                        skipped += count;
                    }
                }
                remaining = spec.length;
                if (remaining == C.LENGTH_UNSET && response.body().contentLength() >= 0)
                    remaining = response.body().contentLength() - skipped;
                opened = true;
                transferStarted(spec);
                return remaining;
            } catch (Exception error) {
                close();
                throw error instanceof IOException ? (IOException) error : new IOException("DASH 请求失败", error);
            }
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) return C.RESULT_END_OF_INPUT;
            int count = input.read(buffer, offset, remaining < 0 ? length : (int) Math.min(length, remaining));
            if (count == -1) return C.RESULT_END_OF_INPUT;
            if (remaining > 0) remaining -= count;
            bytesTransferred(count);
            return count;
        }

        @Override public Uri getUri() { return uri; }

        @Override public void close() {
            if (call != null) { call.cancel(); calls.remove(call); call = null; }
            if (response != null) { response.close(); response = null; }
            input = null;
            if (opened) { opened = false; transferEnded(); }
        }
    }
}
