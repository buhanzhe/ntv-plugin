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
    private android.graphics.Typeface display;
    BoxUi(Context context) {
        super(context);
        try { display=android.graphics.Typeface.createFromAsset(context.getAssets(),"fonts/arphic-gbsn00lp.ttf"); }
        catch(RuntimeException unavailable) { display=android.graphics.Typeface.SERIF; }
    }
    TextView displayTitle(String value) {
        TextView t=text(value,84,TEXT);t.setTypeface(display);t.getPaint().setFakeBoldText(true);
        t.setLineSpacing(px(3),1f);return t;
    }

    @Override public TextView text(String value, int size, int color) {
        TextView t=super.text(value, size, color == BlUi.SUB ? SUB : color == BlUi.TEXT ? TEXT : color);
        t.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));return t;
    }

    GradientDrawable glass(int color, int radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{color, (color & 0xff000000) | 0x14232e});
        d.setCornerRadius(px(radius)); d.setStroke(Math.max(1, px(1)), 0x557f9aaa);
        return d;
    }

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

    StateListDrawable navigationFocus() {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable focused = glass(0xcc536e84, 22);
        focused.setStroke(Math.max(2, px(2)), ACCENT);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        GradientDrawable selected=glass(0xcc697984,22);selected.setStroke(Math.max(1,px(1)),0xbbdae6ee);
        states.addState(new int[]{android.R.attr.state_selected}, selected);
        states.addState(new int[]{}, fill(0x00000000, 22)); return states;
    }

    GradientDrawable silver(int radius) {
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xfff2f7fc,0xffadbdcb,0xffe1eaf2});
        d.setCornerRadius(px(radius));d.setStroke(Math.max(1,px(1)),0xffedf7ff);return d;
    }
    StateListDrawable primaryFocus() {
        StateListDrawable states=new StateListDrawable();
        GradientDrawable focused=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xe6b7c4cf,0xc6788897,0xdd425665});
        focused.setCornerRadius(px(28));focused.setStroke(Math.max(2,px(2)),0xfff6fcff);
        states.addState(new int[]{android.R.attr.state_focused},focused);
        GradientDrawable normal=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xcca2b2bf,0xaa607384,0xbb344a5c});
        normal.setCornerRadius(px(28));normal.setStroke(Math.max(1,px(1)),0x99dae9f4);states.addState(new int[]{},normal);return states;
    }

    Button categoryButton(String value) {
        Button b = new Button(context) {
            private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (isSelected()) {
                    line.setColor(TEXT); line.setStrokeWidth(Math.max(2, px(3))); line.setStrokeCap(Paint.Cap.ROUND);
                    canvas.drawLine(px(16), getHeight()-px(4), getWidth()-px(16), getHeight()-px(4), line);
                }
            }
        };
        b.setText(value); b.setTextColor(TEXT); b.setAllCaps(false);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(28));
        b.setPadding(px(18), 0, px(18), px(6)); b.setMinWidth(0); b.setMinimumWidth(0);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, glass(0x66566e83, 16));
        states.addState(new int[]{}, fill(0x00000000, 0)); b.setBackgroundDrawable(states); return b;
    }

    StateListDrawable episodeBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected}, silver(22));
        states.addState(new int[]{}, glass(0x77415462, 22)); return states;
    }
    android.content.res.ColorStateList episodeText() {
        return new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_selected},new int[]{}},
                new int[]{0xff0d1b26,TEXT});
    }

    GradientDrawable selector() {
        GradientDrawable d = fill(0x00ffffff, 16);
        d.setStroke(Math.max(2, px(2)), ACCENT); return d;
    }

    @Override public Button button(String value) {
        Button b = super.button(value); b.setTextColor(TEXT); b.setBackgroundDrawable(focus(1));
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(22)); return b;
    }

    @Override public EditText editor(String hint, String value) {
        EditText e = super.editor(hint, value); e.setTextColor(TEXT); e.setHintTextColor(SUB); return e;
    }

    @Override public GridView grid(int columns) {
        GridView g = new GridView(context) {
            @Override protected void onMeasure(int width, int height) {
                int count = Math.max(1, Math.min(columns, MeasureSpec.getSize(width) / px(columns >= 5 ? 170 : 210)));
                if (MeasureSpec.getSize(width) > 0 && getNumColumns() != count) setNumColumns(count);
                super.onMeasure(width, height);
            }
        };
        g.setNumColumns(columns); g.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        g.setHorizontalSpacing(px(14)); g.setVerticalSpacing(px(14));
        g.setPadding(px(3), px(12), px(3), px(12)); g.setClipToPadding(false);
        g.setSelector(selector()); g.setDrawSelectorOnTop(true); return g;
    }

    public ImageView imageView() { return imageView(false); }
    ImageView heroImage() { return imageView(true); }
    private ImageView imageView(boolean topCrop) {
        return new ImageView(context) {
            { if (android.os.Build.VERSION.SDK_INT < 18) setLayerType(LAYER_TYPE_SOFTWARE, null);if(topCrop)setScaleType(ScaleType.MATRIX); }
            private final Path outline = new Path();
            private int matrixWidth,matrixHeight,drawableWidth,drawableHeight;
            @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
                super.onSizeChanged(w, h, oldW, oldH);
                outline.reset(); outline.addRoundRect(new RectF(0, 0, w, h), px(16), px(16), Path.Direction.CW);
            }
            @Override protected void onDraw(Canvas canvas) {
                if(topCrop&&getDrawable()!=null&&getDrawable().getIntrinsicWidth()>0&&getDrawable().getIntrinsicHeight()>0&&(matrixWidth!=getWidth()||matrixHeight!=getHeight()||drawableWidth!=getDrawable().getIntrinsicWidth()||drawableHeight!=getDrawable().getIntrinsicHeight())){
                    matrixWidth=getWidth();matrixHeight=getHeight();drawableWidth=getDrawable().getIntrinsicWidth();drawableHeight=getDrawable().getIntrinsicHeight();
                    float scale=Math.max((float)getWidth()/getDrawable().getIntrinsicWidth(),(float)getHeight()/getDrawable().getIntrinsicHeight());
                    android.graphics.Matrix matrix=new android.graphics.Matrix();matrix.setScale(scale,scale);matrix.postTranslate((getWidth()-getDrawable().getIntrinsicWidth()*scale)/2f,0);
                    if(!matrix.equals(getImageMatrix()))setImageMatrix(matrix);
                }
                int save = canvas.save(); canvas.clipPath(outline); super.onDraw(canvas); canvas.restoreToCount(save);
            }
        };
    }

    Drawable navigationIcon(int kind) {
        return new Drawable(){
            private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();
            @Override public void draw(Canvas canvas){int saved=canvas.save();Rect b=getBounds();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);
                paint.setColor(TEXT);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.6f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);path.reset();
                if(kind==3){canvas.drawCircle(10,10,7,paint);canvas.drawLine(15,15,22,22,paint);}
                else if(kind==1){canvas.drawRoundRect(new RectF(2,4,22,20),2,2,paint);path.moveTo(9,8);path.lineTo(16,12);path.lineTo(9,16);path.close();canvas.drawPath(path,paint);}
                else if(kind==0){path.moveTo(3,7);path.lineTo(12,2);path.lineTo(21,7);path.lineTo(12,12);path.close();canvas.drawPath(path,paint);for(int y:new int[]{12,17}){path.reset();path.moveTo(3,y);path.lineTo(12,y+5);path.lineTo(21,y);canvas.drawPath(path,paint);}}
                else if(kind==2){for(int x:new int[]{3,14})for(int y:new int[]{3,14})canvas.drawRoundRect(new RectF(x,y,x+7,y+7),2,2,paint);}
                else{for(int i=0;i<16;i++){double angle=i*Math.PI/8;float radius=i%2==0?10:8;float x=12+(float)Math.cos(angle)*radius,y=12+(float)Math.sin(angle)*radius;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();canvas.drawPath(path,paint);canvas.drawCircle(12,12,3,paint);}
                canvas.restoreToCount(saved);}
            public void setAlpha(int alpha){paint.setAlpha(alpha);}public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        };
    }

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
