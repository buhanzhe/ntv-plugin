package xiao.bu.channel.tvbox;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;

/** A static, artwork-backed glass surface. No screen capture or per-frame blur. */
final class BoxGlass extends Drawable {
    private final View panel, scene;
    private final float radius, stroke;
    private final BoxGlassSkin texture,capsule;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Path outline = new Path();
    private final RectF area = new RectF(), destination = new RectF();
    private final int[] panelPosition = new int[2], scenePosition = new int[2];
    private Bitmap image;
    private BoxGlass source;
    private int style;
    private boolean active, selected;
    private final ColorMatrixColorFilter neutral, cool;
    private final java.util.ArrayList<java.lang.ref.WeakReference<BoxGlass>> controls=new java.util.ArrayList<>();
    private Shader tint, rim, gleam;

    BoxGlass(View panel, View scene, float radius, float stroke,BoxGlassSkin texture,BoxGlassSkin capsule) {
        this.panel=panel; this.scene=scene; this.radius=radius; this.stroke=stroke;
        this.texture=texture;this.capsule=capsule;
        ColorMatrix matrix=new ColorMatrix();matrix.setSaturation(.16f);
        neutral=new ColorMatrixColorFilter(matrix);
        matrix.setSaturation(.42f);matrix.postConcat(new ColorMatrix(new float[]{.94f,0,0,0,0,0,.99f,0,0,2,0,0,1.04f,0,5,0,0,0,1,0}));
        cool=new ColorMatrixColorFilter(matrix);
    }
    BoxGlass control(View view,float radius,int style) {
        BoxGlass control=new BoxGlass(view,scene,radius,stroke,capsule,capsule);control.source=this;control.style=style;
        controls.add(new java.lang.ref.WeakReference<>(control));return control;
    }
    void image(Bitmap value) {
        image=value;invalidateSelf();
        java.util.Iterator<java.lang.ref.WeakReference<BoxGlass>> iterator=controls.iterator();
        while(iterator.hasNext()){BoxGlass control=iterator.next().get();if(control==null)iterator.remove();else control.invalidateSelf();}
    }

    @Override protected void onBoundsChange(Rect bounds) {
        area.set(bounds); area.inset(stroke/2,stroke/2);
        outline.reset(); outline.addRoundRect(area,radius,radius,Path.Direction.CW);
        shaders();
    }
    private void shaders() {
        boolean silver=active&&style>0&&style!=3;
        int[] colors=silver?new int[]{0xfff9fbfd,0xffd9e2ea,0xffedf3f8}:
                style==0?new int[]{0x2b50677d,0x21152638,0x35304456}:
                style==3?new int[]{0x3c8ca7bf,0x2f354c65,0x384f6b83}:
                style==2||selected?new int[]{0x50a3bbce,0x38445870,0x4b455f77}:
                new int[]{0x2d8da9bd,0x24324759,0x323e5368};
        tint=new LinearGradient(0,area.top,0,area.bottom,colors,new float[]{0,.56f,1},Shader.TileMode.CLAMP);
        rim=new LinearGradient(0,area.top,area.right,area.bottom,
                new int[]{active?0xccf7fbff:0x68e3edf5,0x25677b8d,0x20465b70,active?0x99eaf3fc:0x408a9eaf},null,Shader.TileMode.CLAMP);
        gleam=new RadialGradient(area.left,area.top,Math.max(1,area.width()*1.8f),
                new int[]{style==0?0x1fe5f2ff:0x20ffffff,0x00ffffff},null,Shader.TileMode.CLAMP);
    }
    @Override public void draw(Canvas canvas) {
        if(style==3&&!active&&!selected)return;
        Bitmap image=source==null?this.image:source.image;
        int saved=canvas.save(); canvas.clipPath(outline);
        paint.setStyle(Paint.Style.FILL); paint.setShader(null);
        if(image!=null&&!image.isRecycled()&&scene.getWidth()>0&&scene.getHeight()>0){
            panel.getLocationInWindow(panelPosition); scene.getLocationInWindow(scenePosition);
            float scale=Math.max((float)scene.getWidth()/image.getWidth(),(float)scene.getHeight()/image.getHeight());
            float left=(scene.getWidth()-image.getWidth()*scale)/2-(panelPosition[0]-scenePosition[0]);
            float top=(scene.getHeight()-image.getHeight()*scale)/2-(panelPosition[1]-scenePosition[1]);
            destination.set(left,top,left+image.getWidth()*scale,top+image.getHeight()*scale);
            paint.setColor(0x66ffffff);paint.setColorFilter(style==0||style==3?cool:neutral);canvas.drawBitmap(image,null,destination,paint);paint.setColorFilter(null);paint.setAlpha(255);
        }
        paint.setShader(tint); canvas.drawRect(area,paint);
        paint.setShader(gleam); canvas.drawRect(area,paint);
        canvas.restoreToCount(saved);
        paint.setShader(rim); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke);
        canvas.drawRoundRect(area,radius,radius,paint); paint.setShader(null); paint.setStyle(Paint.Style.FILL);
        if(active){
            paint.setColor(0x35ffffff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke*2);
            canvas.drawRoundRect(area,radius,radius,paint);paint.setStyle(Paint.Style.FILL);
        }
        if(texture!=null)texture.draw(canvas,area,radius,style==0?185:active?220:selected?190:160);
    }
    @Override public boolean isStateful(){return style!=0;}
    @Override protected boolean onStateChange(int[] state){
        boolean next=false,chosen=false;for(int value:state){if(value==android.R.attr.state_focused||value==android.R.attr.state_pressed)next=true;if(value==android.R.attr.state_selected)chosen=true;}
        if(next==active&&chosen==selected)return false;active=next;selected=chosen;shaders();invalidateSelf();return true;
    }
    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
