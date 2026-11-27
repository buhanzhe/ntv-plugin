package xiao.bu.channel.tvbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.Button;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import xiao.bu.channel.bl.BlUi;

/** Native glass styling with bounded, static artwork blur; no frame capture. */
final class BoxUi extends BlUi {
    static final int TEXT = 0xfff4f8ff, SUB = 0xffb8cbe1, ACCENT = 0xffe7f6ff;
    static final int HOME_GOLD = 0xffffdfa7;
    private BoxGlass material;
    void material(BoxGlass value){material=value;}
    android.content.res.ColorStateList controlText(){
        return android.content.res.ColorStateList.valueOf(TEXT);
    }
    BoxUi(Context context) { super(context); }
    TextView displayTitle(String value) {
        TextView title = text(value, 80, TEXT);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setLineSpacing(px(6), 1f);
        return title;
    }

    @Override public TextView text(String value, int size, int color) {
        TextView t=super.text(value, size, color == BlUi.SUB ? SUB : color == BlUi.TEXT ? TEXT : color);
        t.setTypeface(android.graphics.Typeface.DEFAULT);
        t.getPaint().setSubpixelText(true);return t;
    }

    GradientDrawable glass(int color, int radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{color, (color & 0xff000000) | 0x14232e});
        d.setCornerRadius(px(radius)); d.setStroke(Math.max(1, px(1)), 0x557f9aaa);
        return d;
    }

    private GradientDrawable capsule(int[] colors) {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors){
            @Override protected void onBoundsChange(Rect bounds){
                super.onBoundsChange(bounds);setCornerRadius(Math.min(bounds.width(),bounds.height())/2f);
            }
        };
    }
    GradientDrawable capsuleGlass(int color){
        GradientDrawable d=capsule(new int[]{color,(color&0xff000000)|0x14232e});
        d.setStroke(Math.max(1,px(1)),0x557f9aaa);return d;
    }
    GradientDrawable capsuleSelector(){
        GradientDrawable d=capsule(new int[]{0,0});d.setStroke(Math.max(2,px(2)),ACCENT);return d;
    }
    Drawable rowGlass(android.view.View row){return material==null?capsuleGlass(0x4035414b):material.control(row,0,4);}

    @Override public StateListDrawable focus(int normal) {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable focused = glass(0xcc506879, 20);
        focused.setStroke(Math.max(2, px(2)), ACCENT);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{android.R.attr.state_pressed}, glass(0xcc455d70, 20));
        states.addState(new int[]{android.R.attr.state_selected}, glass(0xaa41576b, 20));
        states.addState(new int[]{}, glass(normal == 0 ? 0x202e4557 : 0x5533495a, 20));
        return states;
    }

    Button categoryButton(String value) {
        return categoryButton(value, false);
    }

    Button categoryButton(String value, boolean home) {
        Button b = new Button(context);
        b.setText(value); b.setAllCaps(false);
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_selected}, {}}, new int[]{HOME_GOLD, TEXT}));
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(home ? 24 : 28));
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setSingleLine(true);
        b.setMaxWidth(px(180));
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        int inset = px(home ? 11 : 18);
        b.setPadding(inset, 0, inset, 0); b.setMinWidth(0); b.setMinimumWidth(0);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused, android.R.attr.state_selected}, categoryIndicator(true));
        states.addState(new int[]{android.R.attr.state_selected}, categoryIndicator(false));
        states.addState(new int[]{android.R.attr.state_focused}, capsuleGlass(0x66566e83));
        states.addState(new int[]{}, fill(0x00000000, 0)); b.setBackgroundDrawable(states); return b;
    }

    private Drawable categoryIndicator(boolean focused) {
        GradientDrawable surface = focused ? capsuleGlass(0x66566e83) : fill(0, 0);
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.RectF indicator = new android.graphics.RectF();
            @Override public void draw(Canvas canvas) {
                Rect bounds = getBounds();
                surface.setBounds(bounds);
                surface.draw(canvas);
                float halfWidth = Math.min(px(23), bounds.width() / 3f);
                float bottom = bounds.bottom - px(3);
                paint.setColor(HOME_GOLD);
                indicator.set(bounds.exactCenterX() - halfWidth, bottom - px(3),
                        bounds.exactCenterX() + halfWidth, bottom);
                canvas.drawRoundRect(indicator, px(2), px(2), paint);
            }
            @Override public void setAlpha(int alpha) { surface.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) { surface.setColorFilter(filter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    Button homeButton(String label, boolean capsule) {
        Button b = super.button(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(24));
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setPadding(px(capsule ? 14 : 9), 0, px(capsule ? 14 : 9), 0);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        StateListDrawable background = new StateListDrawable();
        GradientDrawable focused = capsule(new int[]{0x993a454a, 0x80202b31});
        focused.setStroke(Math.max(1, px(2)), HOME_GOLD);
        background.addState(new int[]{android.R.attr.state_focused}, focused);
        GradientDrawable normal = capsule(new int[]{0x401f3036, 0x35132028});
        normal.setStroke(Math.max(1, px(2)), 0x45a2abb0);
        background.addState(new int[]{}, capsule ? normal : fill(0, 0));
        b.setBackgroundDrawable(background);
        return b;
    }

    android.content.res.ColorStateList episodeText() {
        return android.content.res.ColorStateList.valueOf(TEXT);
    }

    GradientDrawable selector() {
        GradientDrawable d = fill(0x00ffffff, 16);
        d.setStroke(Math.max(2, px(2)), ACCENT); return d;
    }

    @Override public Button button(String value) {
        Button b = super.button(value); b.setTextColor(controlText());
        b.setBackgroundDrawable(material==null?focus(1):material.control(b,px(22),1));
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(22)); return b;
    }

    @Override public EditText editor(String hint, String value) {
        EditText e = super.editor(hint, value); e.setTextColor(TEXT); e.setHintTextColor(SUB); return e;
    }

    int gridColumns(int columns, int width) {
        return Math.max(1, Math.min(columns, width / px(columns >= 5 ? 170 : 210)));
    }

    @Override public GridView grid(int columns) {
        GridView g = new GridView(context) {
            @Override protected void onMeasure(int width, int height) {
                int count = gridColumns(columns, MeasureSpec.getSize(width));
                if (MeasureSpec.getSize(width) > 0 && getNumColumns() != count) setNumColumns(count);
                super.onMeasure(width, height);
            }
        };
        g.setNumColumns(columns); g.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        g.setHorizontalSpacing(px(18)); g.setVerticalSpacing(px(20));
        g.setPadding(px(3), px(12), px(3), px(12)); g.setClipToPadding(false);
        g.setSelector(selector()); g.setDrawSelectorOnTop(true); return g;
    }

    public BoxImage imageView() { return new BoxImage(context,px(20)); }

    Drawable navigationIcon(int kind) { return icon(kind,false); }
    Drawable actionIcon(int kind) { return icon(kind,true); }
    private Drawable icon(int kind,boolean darkFocus) { return new BoxIcon(kind,darkFocus); }

    Drawable backdrop() {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private Shader base, teal, violet;
            @Override protected void onBoundsChange(Rect bounds) {
                float w = Math.max(1, bounds.width()), h = Math.max(1, bounds.height());
                base = new LinearGradient(0, 0, w, h, new int[]{0xff07121b, 0xff0a1823, 0xff101d27}, null, Shader.TileMode.CLAMP);
                teal = new RadialGradient(w, h * .15f, w * .6f, 0x223c7684, 0x0042b7c8, Shader.TileMode.CLAMP);
                violet = new RadialGradient(0, h, w * .47f, 0x1c466b86, 0x006961c3, Shader.TileMode.CLAMP);
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
