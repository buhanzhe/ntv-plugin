package xiao.bu.channel.files;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.*;
import android.view.*;
import android.widget.*;

/** Cached, static glass materials for Android 4.0: no blur, animation or per-frame allocations. */
final class FileUi {
    static final int INK=0xfff3f7ff, MUTED=0xffafc1db, BLUE=0xff8edbff;
    private static final Typeface FONT=Typeface.create("sans-serif",Typeface.NORMAL);
    private static Bitmap backdrop;
    private static final Bitmap[] icons=new Bitmap[4];
    private static final Rect[] bounds={new Rect(15,16,214,178),new Rect(35,30,190,166),new Rect(35,30,190,190),new Rect(12,24,214,166)};
    static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable plate(int top,int bottom,int radius,int border){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,bottom});d.setCornerRadius(radius);d.setStroke(1,border);return d;
    }
    static Drawable control(Context c,boolean selected){
        int r=dp(c,11);StateListDrawable s=new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed},plate(0x997ac7ff,0x774d8fd8,r,0xffcceeff));
        s.addState(new int[]{android.R.attr.state_focused},plate(0x997cafee,0x88537ab5,r,0xffe3f5ff));
        s.addState(new int[]{},plate(selected?0x887f9fcf:0x284d719b,selected?0x66516d9d:0x18375579,r,selected?0x88bddcff:0x447e9bbd));return s;
    }
    static void button(Button b,boolean selected){
        b.setBackgroundDrawable(control(b.getContext(),selected));b.setTextColor(INK);b.setTextSize(12);b.setGravity(b.getCompoundDrawables()[0]==null?Gravity.CENTER:Gravity.CENTER_VERTICAL|Gravity.LEFT);
        b.setPadding(dp(b.getContext(),10),0,dp(b.getContext(),10),0);b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);
    }
    private static Bitmap background(){
        if(backdrop==null){
            backdrop=Bitmap.createBitmap(256,144,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(backdrop);Paint p=new Paint(3);
            p.setShader(new LinearGradient(0,0,256,144,0xff132747,0xff101b31,Shader.TileMode.CLAMP));c.drawRect(0,0,256,144,p);
            p.setShader(new RadialGradient(255,24,105,new int[]{0xc239c6dc,0x0039c6dc},null,Shader.TileMode.CLAMP));c.drawRect(0,0,256,144,p);
            p.setShader(new RadialGradient(0,144,120,new int[]{0xc47963cb,0x007963cb},null,Shader.TileMode.CLAMP));c.drawRect(0,0,256,144,p);
            p.setShader(new RadialGradient(100,30,120,new int[]{0x553c6bb3,0x003c6bb3},null,Shader.TileMode.CLAMP));c.drawRect(0,0,256,144,p);
            backdrop.setHasAlpha(false);
        }return backdrop;
    }
    static class Surface extends FrameLayout {
        private final Paint paint=new Paint(3);private final Rect target=new Rect();private final Bitmap image;
        Surface(Context c){super(c);setWillNotDraw(false);image=background();}
        protected void onSizeChanged(int w,int h,int ow,int oh){target.set(0,0,w,h);}
        protected void onDraw(Canvas canvas){canvas.drawBitmap(image,null,target,paint);}
        public boolean isOpaque(){return true;}
    }
    static Bitmap glyph(int kind){
        if(icons[kind]!=null)return icons[kind];
        Bitmap image=Bitmap.createBitmap(224,196,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(image);Paint p=new Paint(3);RectF r=new RectF();
        c.scale(224f/120f,196f/106f);
        if(kind==0){
            p.setShader(new LinearGradient(0,10,80,92,0xff9be7ff,0xff3278c4,Shader.TileMode.CLAMP));
            Path back=new Path();back.moveTo(10,29);back.cubicTo(10,15,16,10,29,10);back.lineTo(45,10);back.cubicTo(53,10,56,21,63,21);back.lineTo(94,21);back.cubicTo(104,21,110,27,110,37);back.lineTo(110,82);back.lineTo(10,82);back.close();c.drawPath(back,p);
            p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(0xffc5efff);c.drawPath(back,p);p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(10,30,110,94,new int[]{0xff90dbf4,0xff458bcb,0xff81b9ed},new float[]{0,.65f,1},Shader.TileMode.CLAMP));r.set(9,28,112,94);c.drawRoundRect(r,15,15,p);
            p.setShader(null);p.setColor(0xffb7edff);p.setStyle(Paint.Style.STROKE);c.drawRoundRect(r,15,15,p);p.setStyle(Paint.Style.FILL);
            p.setColor(0x33d6f7ff);r.set(13,30,108,40);c.drawRoundRect(r,7,7,p);
        }else if(kind==3){
            p.setShader(new LinearGradient(8,15,113,87,0xff3c73a7,0xff685db1,Shader.TileMode.CLAMP));r.set(7,14,113,88);c.drawRoundRect(r,12,12,p);
            p.setShader(null);p.setColor(0xffa8d3f4);p.setStyle(Paint.Style.STROKE);c.drawRoundRect(r,12,12,p);p.setStyle(Paint.Style.FILL);
            p.setColor(0xffdcefff);Path triangle=new Path();triangle.moveTo(51,36);triangle.lineTo(51,67);triangle.lineTo(76,52);triangle.close();c.drawPath(triangle,p);
            for(int x=16;x<110;x+=17){r.set(x,19,x+8,23);c.drawRoundRect(r,1,1,p);}
        }else{
            p.setShader(new LinearGradient(20,15,99,90,kind==2?0xff92ddff:0xffdcebf7,kind==2?0xff2e73b3:0xff718cae,Shader.TileMode.CLAMP));
            r.set(20,17,100,87);c.drawRoundRect(r,13,13,p);p.setShader(null);p.setColor(0xffd6efff);p.setStyle(Paint.Style.STROKE);c.drawRoundRect(r,13,13,p);p.setStyle(Paint.Style.FILL);
            p.setColor(0x77f2faff);r.set(26,24,94,63);c.drawRoundRect(r,7,7,p);p.setColor(0xff547497);r.set(29,72,71,76);c.drawRoundRect(r,2,2,p);p.setColor(0xff9ce2bd);c.drawCircle(84,74,3,p);
            if(kind==2){p.setStrokeWidth(2);p.setColor(0xffbce9ff);c.drawLine(60,87,60,101,p);c.drawLine(38,101,82,101,p);}
        }
        icons[kind]=image;return image;
    }
    static View appIcon(Context context){return new View(context){final Paint p=new Paint(3);final Rect dst=new Rect();protected void onDraw(Canvas c){dst.set(0,0,getWidth(),getHeight());c.drawBitmap(glyph(0),null,dst,p);}};}
    static final class Card extends View {
        private final Paint p=new Paint(3);private final TextPaint text=new TextPaint(3);private final RectF r=new RectF();private final Rect iconRect=new Rect();
        private String title="",detail="",shortTitle="",shortDetail="";private int kind;private final float density;
        Card(Context c){super(c);density=getResources().getDisplayMetrics().density;text.setTypeface(FONT);}
        void bind(String title,String detail,int kind){if(this.title.equals(title)&&this.detail.equals(detail)&&this.kind==kind)return;this.title=title;this.detail=detail;this.kind=kind;glyph(kind);setContentDescription(title+"，"+detail);fit();invalidate();}
        private void fit(){float width=Math.max(1,getWidth()-16*density);text.setTextSize(14*density);shortTitle=TextUtils.ellipsize(title,text,width,TextUtils.TruncateAt.END).toString();text.setTextSize(10*density);shortDetail=TextUtils.ellipsize(detail,text,width,TextUtils.TruncateAt.END).toString();Rect src=bounds[kind];float scale=Math.min(Math.max(1,Math.min(width,dp(getContext(),kind==0?108:kind==3?150:84)))/src.width(),dp(getContext(),82)/(float)src.height());int iw=Math.max(1,Math.round(src.width()*scale)),ih=Math.max(1,Math.round(src.height()*scale));iconRect.set(dp(getContext(),8),dp(getContext(),90)-ih,dp(getContext(),8)+iw,dp(getContext(),90));}
        protected void onSizeChanged(int w,int h,int ow,int oh){fit();}
        protected void drawableStateChanged(){super.drawableStateChanged();invalidate();}
        public void setSelected(boolean selected){if(selected==isSelected())return;super.setSelected(selected);invalidate();}
        protected void onDraw(Canvas c){
            boolean focus=isPressed()||(isSelected()&&getParent() instanceof View&&((View)getParent()).hasFocus());
            if(focus){p.setColor(0x55466d9e);p.setStyle(Paint.Style.FILL);r.set(density,density,getWidth()-density,getHeight()-density);c.drawRoundRect(r,12*density,12*density,p);p.setColor(0xffd8f3ff);p.setStrokeWidth(1.5f*density);p.setStyle(Paint.Style.STROKE);c.drawRoundRect(r,12*density,12*density,p);p.setStyle(Paint.Style.FILL);}
            c.drawBitmap(glyph(kind),bounds[kind],iconRect,p);text.setColor(INK);text.setTextSize(14*density);c.drawText(shortTitle,8*density,112*density,text);text.setColor(MUTED);text.setTextSize(10*density);c.drawText(shortDetail,8*density,130*density,text);
        }
    }
}
