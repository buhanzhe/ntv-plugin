package xiao.bu.channel.tvbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import xiao.bu.channel.bl.BlUi;

/** Lightweight glass styling: cached gradients, no frame capture or live blur. */
final class BoxUi extends BlUi {
    static final int TEXT = 0xfff4f8ff, SUB = 0xffb8cbe1, ACCENT = 0xffe7f6ff;
    BoxUi(Context context) { super(context); }

    @Override public TextView text(String value, int size, int color) {
        return super.text(value, size, color == BlUi.SUB ? SUB : color == BlUi.TEXT ? TEXT : color);
    }

    GradientDrawable glass(int color, int radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{color, (color & 0xff000000) | 0x183650});
        d.setCornerRadius(px(radius)); d.setStroke(Math.max(1, px(1)), 0x557dadd4);
        return d;
    }

    @Override public StateListDrawable focus(int normal) {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable focused = glass(0xbb46769e, 16);
        focused.setStroke(Math.max(2, px(2)), ACCENT);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{android.R.attr.state_pressed}, glass(0xcc456e96, 16));
        states.addState(new int[]{android.R.attr.state_selected}, glass(0x994d729b, 16));
        states.addState(new int[]{}, glass(normal == 0 ? 0x203f6687 : 0x55486c90, 16));
        return states;
    }

    GradientDrawable selector() {
        GradientDrawable d = fill(0x194b9bc7, 16);
        d.setStroke(Math.max(2, px(2)), ACCENT); return d;
    }

    @Override public Button button(String value) {
        Button b = super.button(value); b.setTextColor(TEXT); b.setBackgroundDrawable(focus(1)); return b;
    }

    @Override public EditText editor(String hint, String value) {
        EditText e = super.editor(hint, value); e.setTextColor(TEXT); e.setHintTextColor(SUB); return e;
    }

    @Override public GridView grid(int columns) {
        GridView g = new GridView(context) {
            @Override protected void onMeasure(int width, int height) {
                int count = Math.max(1, Math.min(columns, MeasureSpec.getSize(width) / px(210)));
                if (MeasureSpec.getSize(width) > 0 && getNumColumns() != count) setNumColumns(count);
                super.onMeasure(width, height);
            }
        };
        g.setNumColumns(columns); g.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        g.setHorizontalSpacing(px(14)); g.setVerticalSpacing(px(14));
        g.setPadding(px(3), px(12), px(3), px(12)); g.setClipToPadding(false);
        g.setSelector(selector()); g.setDrawSelectorOnTop(true); return g;
    }

    @Override public ImageView imageView() {
        return new ImageView(context) {
            { if (android.os.Build.VERSION.SDK_INT < 18) setLayerType(LAYER_TYPE_SOFTWARE, null); }
            private final Path outline = new Path();
            @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
                super.onSizeChanged(w, h, oldW, oldH);
                outline.reset(); outline.addRoundRect(new RectF(0, 0, w, h), px(16), px(16), Path.Direction.CW);
            }
            @Override protected void onDraw(Canvas canvas) {
                int save = canvas.save(); canvas.clipPath(outline); super.onDraw(canvas); canvas.restoreToCount(save);
            }
        };
    }

    Drawable backdrop() {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private Shader base, teal, violet;
            @Override protected void onBoundsChange(Rect bounds) {
                float w = Math.max(1, bounds.width()), h = Math.max(1, bounds.height());
                base = new LinearGradient(0, 0, w, h, new int[]{0xff162c50, 0xff08192b, 0xff142d48}, null, Shader.TileMode.CLAMP);
                teal = new RadialGradient(w, h * .15f, w * .6f, 0x8842b7c8, 0x0042b7c8, Shader.TileMode.CLAMP);
                violet = new RadialGradient(0, h, w * .47f, 0x886961c3, 0x006961c3, Shader.TileMode.CLAMP);
            }
            @Override public void draw(Canvas canvas) {
                paint.setShader(base); canvas.drawRect(getBounds(), paint);
                paint.setShader(teal); canvas.drawRect(getBounds(), paint);
                paint.setShader(violet); canvas.drawRect(getBounds(), paint);
            }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(ColorFilter filter) { }
            @Override public int getOpacity() { return PixelFormat.OPAQUE; }
        };
    }
}
