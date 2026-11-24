package xiao.bu.channel.tvbox;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Landscape captions sit on artwork; poster metadata sits below. */
final class BoxTile extends LinearLayout {
    private final ImageView cover;
    private final FrameLayout artwork;
    private final TextView title, subtitle, caption;
    private final BoxUi ui;
    private final Paint ring=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF outline=new RectF();
    private boolean portrait;
    BoxTile(BoxUi ui) {
        super(ui.context);this.ui=ui;setOrientation(VERTICAL);
        setPadding(ui.px(4),ui.px(4),ui.px(4),ui.px(8));setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        artwork=new FrameLayout(ui.context);cover=ui.imageView();cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundDrawable(ui.glass(0x55334758,16));artwork.addView(cover,new FrameLayout.LayoutParams(-1,-1));
        caption=ui.text("",25,BoxUi.TEXT);ui.displayCaption(caption);caption.setGravity(Gravity.LEFT|Gravity.BOTTOM);
        caption.setSingleLine(true);caption.setEllipsize(TextUtils.TruncateAt.END);caption.setPadding(ui.px(12),ui.px(20),ui.px(10),ui.px(10));
        GradientDrawable shade=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00000000,0xe6071019});shade.setCornerRadius(ui.px(20));caption.setBackgroundDrawable(shade);
        artwork.addView(caption,new FrameLayout.LayoutParams(-1,ui.px(72),Gravity.BOTTOM));addView(artwork,new LinearLayout.LayoutParams(-1,ui.px(130)));
        title=ui.text("",26,BoxUi.TEXT);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);title.setPadding(ui.px(4),ui.px(8),ui.px(4),0);addView(title,new LinearLayout.LayoutParams(-1,ui.px(40)));
        subtitle=ui.text("",18,BoxUi.SUB);subtitle.setSingleLine(true);subtitle.setEllipsize(TextUtils.TruncateAt.END);subtitle.setPadding(ui.px(4),0,ui.px(4),0);addView(subtitle,new LinearLayout.LayoutParams(-1,ui.px(28)));
    }
    ImageView bind(String name,String facts,String remark,boolean portrait) {
        this.portrait=portrait;title.setText(name);caption.setText(name);subtitle.setText(facts.isEmpty()?remark:facts);
        caption.setVisibility(portrait?GONE:VISIBLE);title.setVisibility(portrait?VISIBLE:GONE);subtitle.setVisibility(portrait?VISIBLE:GONE);
        setContentDescription(name+(facts.isEmpty()?"":" · "+facts));return cover;
    }
    @Override protected void onMeasure(int width,int height) {
        int available=Math.max(1,MeasureSpec.getSize(width)-getPaddingLeft()-getPaddingRight());artwork.getLayoutParams().height=portrait?available*3/2:available*5/8;super.onMeasure(width,height);
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if(!isSelected()||!(getParent() instanceof android.view.View)||!((android.view.View)getParent()).hasFocus())return;
        outline.set(artwork.getLeft(),artwork.getTop(),artwork.getRight(),artwork.getBottom());ring.setStyle(Paint.Style.STROKE);ring.setStrokeWidth(Math.max(3,ui.px(5)));ring.setColor(0x33e8f5ff);canvas.drawRoundRect(outline,ui.px(20),ui.px(20),ring);
        ring.setStrokeWidth(Math.max(1.5f,ui.px(2)));ring.setColor(0xfff0f8ff);canvas.drawRoundRect(outline,ui.px(20),ui.px(20),ring);
    }
}
