package xiao.bu.channel.tvbox;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Landscape captions sit on artwork; poster metadata sits below. */
final class BoxTile extends ViewGroup {
    private final BoxImage cover;
    private final TextView title, subtitle;
    private final BoxUi ui;
    private final Paint ring=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF outline=new RectF();
    private boolean portrait;
    private boolean configured;
    private final int titleHeight, subtitleHeight, captionHeight;
    private int artworkHeight;
    private String coverUrl = "";
    private int coverEdge;
    BoxTile(BoxUi ui) {
        super(ui.context);
        this.ui = ui;
        titleHeight = ui.px(40);
        subtitleHeight = ui.px(28);
        captionHeight = ui.px(72);
        setPadding(ui.px(4),ui.px(4),ui.px(4),ui.px(8));setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        cover = ui.imageView();
        cover.setScaleType(BoxImage.ScaleType.CENTER_CROP);
        cover.setPlaceholder(ui.glass(0x55334758, 16));
        addView(cover);
        title = ui.text("", 26, BoxUi.TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, titleHeight));
        subtitle = ui.text("", 18, BoxUi.SUB);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setPadding(ui.px(4), 0, ui.px(4), 0);
        addView(subtitle, new LayoutParams(LayoutParams.MATCH_PARENT, subtitleHeight));
    }
    void bind(String name,String facts,String remark,boolean portrait) {
        if (!configured || this.portrait != portrait) {
            configured = true;
            this.portrait = portrait;
            title.getLayoutParams().height = portrait ? titleHeight : captionHeight;
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, ui.px(portrait ? 26 : 25));
            title.setGravity(Gravity.LEFT | (portrait ? Gravity.CENTER_VERTICAL : Gravity.BOTTOM));
            title.setPadding(ui.px(portrait ? 4 : 12), ui.px(portrait ? 8 : 20),
                    ui.px(portrait ? 4 : 10), portrait ? 0 : ui.px(10));
            if (portrait) title.setBackgroundDrawable(null);
            else {
                GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x00000000, 0xe6071019});
                shade.setCornerRadius(ui.px(20));
                title.setBackgroundDrawable(shade);
            }
            subtitle.setVisibility(portrait ? VISIBLE : GONE);
        }
        text(title, name);
        if (portrait) text(subtitle, facts.isEmpty() ? remark : facts);
    }
    private static void text(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }
    void bindCover(BoxArt art, String url, int edge, boolean deferred) {
        if (coverEdge == edge && coverUrl.equals(url)) return;
        coverUrl = url;
        coverEdge = edge;
        if (deferred) art.prepare(cover, url, edge);
        else art.load(cover, url, edge);
    }
    void loadCover(BoxArt art) { art.load(cover, coverUrl, coverEdge); }
    String coverUrl() { return coverUrl; }
    void loadCover(BoxArt art, BoxArt.Ready ready) { art.load(cover, coverUrl, coverEdge, ready); }
    void releaseCover(BoxArt art) { art.cancelPending(cover); }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int measuredWidth = MeasureSpec.getSize(widthSpec);
        int width = Math.max(1, measuredWidth - getPaddingLeft() - getPaddingRight());
        artworkHeight = portrait ? width * 3 / 2 : width * 5 / 8;
        int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        measureChildHeight(cover, childWidth, artworkHeight);
        measureChildHeight(title, childWidth, portrait ? titleHeight : Math.min(captionHeight, artworkHeight));
        if (portrait) measureChildHeight(subtitle, childWidth, subtitleHeight);
        int desiredHeight = getPaddingTop() + artworkHeight + getPaddingBottom()
                + (portrait ? titleHeight + subtitleHeight : 0);
        setMeasuredDimension(measuredWidth, resolveSize(desiredHeight, heightSpec));
    }
    private static void measureChildHeight(View view, int widthSpec, int height) {
        view.measure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int x = getPaddingLeft(), y = getPaddingTop();
        int end = x + cover.getMeasuredWidth(), artBottom = y + artworkHeight;
        cover.layout(x, y, end, artBottom);
        int titleTop = portrait ? artBottom : artBottom - title.getMeasuredHeight();
        title.layout(x, titleTop, end, titleTop + title.getMeasuredHeight());
        if (portrait) {
            int next = title.getBottom();
            subtitle.layout(x, next, end, next + subtitleHeight);
        }
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if(!isSelected()||!(getParent() instanceof android.view.View)||!((android.view.View)getParent()).hasFocus())return;
        outline.set(cover.getLeft(),cover.getTop(),cover.getRight(),cover.getBottom());ring.setStyle(Paint.Style.STROKE);ring.setStrokeWidth(Math.max(3,ui.px(5)));ring.setColor(0x33e8f5ff);canvas.drawRoundRect(outline,ui.px(20),ui.px(20),ring);
        ring.setStrokeWidth(Math.max(1.5f,ui.px(2)));ring.setColor(0xfff0f8ff);canvas.drawRoundRect(outline,ui.px(20),ui.px(20),ring);
    }
}
