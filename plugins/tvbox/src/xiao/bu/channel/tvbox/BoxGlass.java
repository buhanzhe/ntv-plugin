package xiao.bu.channel.tvbox;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Background-coloured frost and a quiet directional rim, sampled only on scene/layout changes. */
final class BoxGlass extends Drawable {
    private final View panel,scene;
    private final float radius,stroke;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);
    private final RectF area=new RectF();
    private final Matrix transform=new Matrix();
    private final int[] panelPosition=new int[2],scenePosition=new int[2];
    private final java.util.ArrayList<java.lang.ref.WeakReference<BoxGlass>> controls=new java.util.ArrayList<>();
    private final ColorMatrixColorFilter tone;
    private Bitmap image,sampled;
    private BitmapShader imageShader;
    private BoxGlass source;
    private int style,opacity=255;
    private boolean active,selected;
    private Shader rim,sheen;
    private float corner,luminance=.1f,complexity;
    private long samplePosition=Long.MIN_VALUE;

    BoxGlass(View panel,View scene,float radius,float stroke){
        this.panel=panel;this.scene=scene;this.radius=radius;this.stroke=stroke;
        ColorMatrix matrix=new ColorMatrix();matrix.setSaturation(.9f);
        matrix.postConcat(new ColorMatrix(new float[]{.88f,0,0,0,0,0,.88f,0,0,0,0,0,.88f,0,0,0,0,0,1,0}));
        tone=new ColorMatrixColorFilter(matrix);
    }
    BoxGlass control(View view,float radius,int style){
        BoxGlass control=new BoxGlass(view,scene,radius,stroke);control.source=this;control.style=style;
        controls.add(new java.lang.ref.WeakReference<>(control));return control;
    }
    void image(Bitmap value){
        image=value;invalidateSelf();
        java.util.Iterator<java.lang.ref.WeakReference<BoxGlass>> iterator=controls.iterator();
        while(iterator.hasNext()){BoxGlass control=iterator.next().get();if(control==null)iterator.remove();else control.invalidateSelf();}
    }
    @Override protected void onBoundsChange(Rect bounds){
        area.set(bounds);area.inset(stroke/2,stroke/2);
        corner=style==0?Math.min(radius,Math.min(area.width(),area.height())/2):Math.min(area.width(),area.height())/2;
        samplePosition=Long.MIN_VALUE;
        rim=new LinearGradient(area.left,area.top,area.right,area.bottom,
                new int[]{0xa0ffffff,0x16ffffff,0x08ffffff,0x50ffffff},new float[]{0,.35f,.7f,1},Shader.TileMode.CLAMP);
        sheen=new LinearGradient(0,area.top,0,area.bottom,new int[]{0x1cffffff,0x00ffffff,0x04ffffff},new float[]{0,.55f,1},Shader.TileMode.CLAMP);
    }
    private void sample(Bitmap bitmap,float scale,float left,float top){
        long position=((long)panelPosition[0]<<32)^(panelPosition[1]&0xffffffffL)^((long)scene.getWidth()<<16)^scene.getHeight();
        if(sampled==bitmap&&samplePosition==position)return;
        sampled=bitmap;samplePosition=position;imageShader=new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        float total=0,low=1,high=0;
        for(int y=0;y<3;y++)for(int x=0;x<5;x++){
            int ix=Math.max(0,Math.min(bitmap.getWidth()-1,Math.round((area.left+area.width()*(x+1)/6-left)/scale)));
            int iy=Math.max(0,Math.min(bitmap.getHeight()-1,Math.round((area.top+area.height()*(y+1)/4-top)/scale)));
            int color=bitmap.getPixel(ix,iy);float l=(((color>>16)&255)*.299f+((color>>8)&255)*.587f+(color&255)*.114f)/255;
            total+=l;low=Math.min(low,l);high=Math.max(high,l);
        }
        luminance=(total-low-high)/13;complexity=high-low;
    }
    private int alpha(float value){return Math.max(0,Math.min(255,Math.round(value*opacity/255)));}
    @Override public void draw(Canvas canvas){
        if(area.isEmpty()||(style==3&&!active&&!selected))return;
        Bitmap bitmap=source==null?image:source.image;
        paint.setStyle(Paint.Style.FILL);paint.setShader(null);paint.setColorFilter(null);
        if(bitmap!=null&&!bitmap.isRecycled()&&scene.getWidth()>0&&scene.getHeight()>0){
            panel.getLocationInWindow(panelPosition);scene.getLocationInWindow(scenePosition);
            float scale=Math.max((float)scene.getWidth()/bitmap.getWidth(),(float)scene.getHeight()/bitmap.getHeight());
            float left=(scene.getWidth()-bitmap.getWidth()*scale)/2-(panelPosition[0]-scenePosition[0]);
            float top=(scene.getHeight()-bitmap.getHeight()*scale)/2-(panelPosition[1]-scenePosition[1]);
            sample(bitmap,scale,left,top);
            // A mild lens enlargement; the centre and tint still come from this exact backdrop region.
            float zoom=1.015f;transform.setScale(scale*zoom,scale*zoom);
            transform.postTranslate(area.centerX()+(left-area.centerX())*zoom,area.centerY()+(top-area.centerY())*zoom);imageShader.setLocalMatrix(transform);
            paint.setShader(imageShader);paint.setColorFilter(tone);paint.setAlpha(alpha(style==0?200:184));
            canvas.drawRoundRect(area,corner,corner,paint);paint.setColorFilter(null);paint.setShader(null);
        }
        // Dark-theme ink stays legible over bright art without turning every surface blue or metallic.
        int veil=bitmap==null?62:Math.round(26+90*luminance+28*complexity);
        paint.setColor(Color.argb(alpha(veil),12,16,21));canvas.drawRoundRect(area,corner,corner,paint);
        int light=active?(luminance>.5f?38:72):selected?32:style==2?14:0;
        if(light>0){paint.setColor(Color.argb(alpha(light),255,255,255));canvas.drawRoundRect(area,corner,corner,paint);}
        paint.setShader(sheen);paint.setAlpha(alpha(style==0?150:active?160:90));canvas.drawRoundRect(area,corner,corner,paint);
        paint.setShader(rim);paint.setAlpha(alpha(active?235:selected?145:style==0?110:85));
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);
        canvas.drawRoundRect(area,corner,corner,paint);
        paint.setStyle(Paint.Style.FILL);paint.setShader(null);paint.setAlpha(255);
    }
    @Override public boolean isStateful(){return style!=0;}
    @Override protected boolean onStateChange(int[] states){
        boolean focused=false,chosen=false;for(int state:states){focused|=state==android.R.attr.state_focused||state==android.R.attr.state_pressed;chosen|=state==android.R.attr.state_selected;}
        if(active==focused&&selected==chosen)return false;active=focused;selected=chosen;invalidateSelf();return true;
    }
    @Override public void setAlpha(int value){opacity=Math.max(0,Math.min(255,value));invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
