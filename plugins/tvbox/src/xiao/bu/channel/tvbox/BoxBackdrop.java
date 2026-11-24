package xiao.bu.channel.tvbox;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/** Wide artwork fills the scene; portrait or tiny posters retain their composition at the right. */
final class BoxBackdrop extends ImageView {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);
    private final Matrix matrix=new Matrix();
    private final RectF area=new RectF();
    private final float radius;
    private Bitmap current;
    private int width,height;
    BoxBackdrop(Context context,float radius){super(context);this.radius=radius;}
    @Override protected void onDraw(Canvas canvas){
        if(!(getDrawable() instanceof BitmapDrawable))return;
        Bitmap bitmap=((BitmapDrawable)getDrawable()).getBitmap();if(bitmap==null||bitmap.isRecycled())return;
        if(bitmap!=current||width!=getWidth()||height!=getHeight()){
            current=bitmap;width=getWidth();height=getHeight();
            boolean poster=bitmap.getWidth()<bitmap.getHeight()*1.15f||bitmap.getWidth()<width*.6f;
            float scale=poster?Math.min(height*.86f/bitmap.getHeight(),1.6f):Math.max((float)width/bitmap.getWidth(),(float)height/bitmap.getHeight());
            float left=poster?width-bitmap.getWidth()*scale:(width-bitmap.getWidth()*scale)/2;
            float top=poster?height-bitmap.getHeight()*scale:(height-bitmap.getHeight()*scale)/2;
            matrix.setScale(scale,scale);matrix.postTranslate(left,top);
            BitmapShader image=new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);image.setLocalMatrix(matrix);
            if(poster){
                area.set(Math.max(0,left),Math.max(0,top),width,Math.min(height,top+bitmap.getHeight()*scale));
                Shader fade=new LinearGradient(area.left,0,area.left+Math.max(1,area.width()*.45f),0,0x00ffffff,0xffffffff,Shader.TileMode.CLAMP);
                paint.setShader(new ComposeShader(image,fade,PorterDuff.Mode.DST_IN));
            }else{area.set(0,0,width,height);paint.setShader(image);}
        }
        canvas.drawRoundRect(area,radius,radius,paint);
    }
    static Drawable scrim(boolean cinema){return new Drawable(){
        final Paint paint=new Paint(Paint.DITHER_FLAG);Shader horizontal,vertical;
        protected void onBoundsChange(Rect bounds){
            float w=Math.max(1,bounds.width()),h=Math.max(1,bounds.height());
            horizontal=new LinearGradient(0,0,w,0,cinema?new int[]{0xf5060c12,0xb0060c12,0x18060c12}:new int[]{0xc509111a,0xbc09111a},cinema?new float[]{0,.42f,1}:null,Shader.TileMode.CLAMP);
            vertical=new LinearGradient(0,0,0,h,new int[]{0x10040a10,0x00040a10,0xeb040a10},new float[]{0,.45f,1},Shader.TileMode.CLAMP);
        }
        public void draw(Canvas canvas){paint.setShader(horizontal);canvas.drawRect(getBounds(),paint);paint.setShader(vertical);canvas.drawRect(getBounds(),paint);}
        public void setAlpha(int alpha){}public void setColorFilter(ColorFilter filter){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    };}
}
