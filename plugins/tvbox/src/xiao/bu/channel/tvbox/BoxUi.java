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

/** Native glass styling with bounded, static artwork blur; no frame capture. */
final class BoxUi extends BlUi {
    static final int TEXT = 0xfff4f8ff, SUB = 0xffb8cbe1, ACCENT = 0xffe7f6ff;
    private final BoxTypography typography;
    private BoxGlass material;
    void material(BoxGlass value){material=value;}
    Drawable navigationFocus(Button button){return material==null?navigationFocus():material.control(button,px(22),3);}
    Drawable primaryFocus(Button button){return material==null?primaryFocus():material.control(button,px(28),2);}
    android.content.res.ColorStateList controlText(){
        return android.content.res.ColorStateList.valueOf(TEXT);
    }
    BoxUi(Context context,ClassLoader assets,okhttp3.OkHttpClient transport) {
        super(context);
        typography=new BoxTypography(context,transport);
    }
    TextView displayTitle(String value) {
        TextView t=text(value,80,TEXT);typography.title(t);
        t.setLineSpacing(px(6),1f);return t;
    }
    void close(){typography.close();}
    void displayCaption(TextView view){typography.title(view);}

    @Override public TextView text(String value, int size, int color) {
        TextView t=super.text(value, size, color == BlUi.SUB ? SUB : color == BlUi.TEXT ? TEXT : color);
        t.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));
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

    StateListDrawable navigationFocus() {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable focused = glass(0xcc536e84, 22);
        focused.setStroke(Math.max(2, px(2)), ACCENT);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        GradientDrawable selected=glass(0xcc697984,22);selected.setStroke(Math.max(1,px(1)),0xbbdae6ee);
        states.addState(new int[]{android.R.attr.state_selected}, selected);
        states.addState(new int[]{}, fill(0x00000000, 22)); return states;
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
        states.addState(new int[]{android.R.attr.state_focused}, capsuleGlass(0x66566e83));
        states.addState(new int[]{}, fill(0x00000000, 0)); b.setBackgroundDrawable(states); return b;
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

    @Override public GridView grid(int columns) {
        GridView g = new GridView(context) {
            @Override protected void onMeasure(int width, int height) {
                int count = Math.max(1, Math.min(columns, MeasureSpec.getSize(width) / px(columns >= 5 ? 170 : 210)));
                if (MeasureSpec.getSize(width) > 0 && getNumColumns() != count) setNumColumns(count);
                super.onMeasure(width, height);
            }
        };
        g.setNumColumns(columns); g.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        g.setHorizontalSpacing(px(18)); g.setVerticalSpacing(px(20));
        g.setPadding(px(3), px(12), px(3), px(12)); g.setClipToPadding(false);
        g.setSelector(selector()); g.setDrawSelectorOnTop(true); return g;
    }

    public ImageView imageView() { return new BoxImage(context,px(20)); }
    ImageView heroImage() { return new BoxBackdrop(context,px(24)); }

    Drawable navigationIcon(int kind) { return icon(kind,false); }
    Drawable actionIcon(int kind) { return icon(kind,true); }
    private Drawable icon(int kind,boolean darkFocus) {
        return new Drawable(){
            private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();
            private final RectF rect=new RectF();
            @Override public void draw(Canvas canvas){int saved=canvas.save();Rect b=getBounds();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);
                boolean focus=false;for(int state:getState())if(state==android.R.attr.state_focused||state==android.R.attr.state_pressed)focus=true;
                paint.setColor(TEXT);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.65f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);path.reset();
                if(kind==3){canvas.drawCircle(10.3f,10.3f,7.2f,paint);canvas.drawLine(15.6f,15.6f,21.6f,21.6f,paint);}
                else if(kind==1){
                    canvas.drawCircle(12,12,9.6f,paint);
                    paint.setStrokeWidth(1.3f);
                    for(int x=0;x<2;x++)for(int y=0;y<2;y++)canvas.drawCircle(8.35f+x*7.3f,8.35f+y*7.3f,2.35f,paint);
                    paint.setStyle(Paint.Style.FILL);canvas.drawCircle(12,12,.8f,paint);
                }
                else if(kind==0){path.moveTo(3,7);path.lineTo(12,2.5f);path.lineTo(21,7);path.lineTo(12,11.5f);path.close();canvas.drawPath(path,paint);for(int y=12;y<=17;y+=5){path.reset();path.moveTo(3,y);path.lineTo(12,y+4.5f);path.lineTo(21,y);canvas.drawPath(path,paint);}}
                else if(kind==2){for(int x=3;x<=14;x+=11)for(int y=3;y<=14;y+=11){rect.set(x,y,x+7,y+7);canvas.drawRoundRect(rect,2.2f,2.2f,paint);}}
                else if(kind==5){path.moveTo(14.5f,4.5f);path.lineTo(7,12);path.lineTo(14.5f,19.5f);canvas.drawPath(path,paint);}
                else if(kind==7){path.moveTo(7,4);path.lineTo(20,12);path.lineTo(7,20);path.close();paint.setStyle(Paint.Style.FILL);canvas.drawPath(path,paint);}
                else if(kind==8){path.moveTo(3,4);path.lineTo(21,4);path.lineTo(14,12);path.lineTo(14,19);path.lineTo(10,21);path.lineTo(10,12);path.close();canvas.drawPath(path,paint);}
                else{for(int i=0;i<32;i++){double a=i*Math.PI/16;float radius=i%4==0||i%4==3?10:8.25f;float x=12+(float)Math.cos(a)*radius,y=12+(float)Math.sin(a)*radius;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();canvas.drawPath(path,paint);canvas.drawCircle(12,12,3.5f,paint);}
                canvas.restoreToCount(saved);}
            public boolean isStateful(){return darkFocus;}protected boolean onStateChange(int[] state){invalidateSelf();return true;}
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
