package xiao.bu.channel.tvbox;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/** Shader-drawn artwork gives antialiased corners on KitKat, without a hard clip mask. */
class BoxImage extends ImageView {
    protected final Paint imagePaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);
    protected final RectF imageBounds=new RectF();
    protected final Matrix imageTransform=new Matrix();
    private final float radius;
    private Bitmap current;
    private int width,height;
    private ScaleType mode;
    private Drawable placeholder;
    BoxImage(Context context,float radius){super(context);this.radius=radius;}
    void setPlaceholder(Drawable drawable) { placeholder = drawable; invalidate(); }
    boolean hasPlaceholder() { return placeholder != null; }
    @Override public void setImageDrawable(android.graphics.drawable.Drawable drawable) {
        super.setImageDrawable(drawable);
        if (drawable == null) {
            current = null;
            if (imagePaint != null) imagePaint.setShader(null);
        }
    }
    @Override protected void onDraw(Canvas canvas){
        Drawable drawable = getDrawable();
        if (!(drawable instanceof BitmapDrawable)) {
            if (drawable == null) drawPlaceholder(canvas);
            else super.onDraw(canvas);
            return;
        }
        Bitmap bitmap=((BitmapDrawable)drawable).getBitmap();if(bitmap==null||bitmap.isRecycled()){drawPlaceholder(canvas);return;}
        if (placeholder != null && (bitmap.hasAlpha() || getScaleType() == ScaleType.FIT_CENTER)) drawPlaceholder(canvas);
        if(bitmap!=current||width!=getWidth()||height!=getHeight()||mode!=getScaleType()){
            current=bitmap;width=getWidth();height=getHeight();mode=getScaleType();
            float scale=mode==ScaleType.FIT_CENTER?Math.min((float)width/bitmap.getWidth(),(float)height/bitmap.getHeight()):Math.max((float)width/bitmap.getWidth(),(float)height/bitmap.getHeight());
            float left=(width-bitmap.getWidth()*scale)/2,top=(height-bitmap.getHeight()*scale)/2;
            imageBounds.set(mode==ScaleType.FIT_CENTER?left:0,mode==ScaleType.FIT_CENTER?top:0,mode==ScaleType.FIT_CENTER?width-left:width,mode==ScaleType.FIT_CENTER?height-top:height);
            imageTransform.setScale(scale,scale);imageTransform.postTranslate(left,top);
            BitmapShader shader=new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);shader.setLocalMatrix(imageTransform);imagePaint.setShader(shader);
        }
        canvas.drawRoundRect(imageBounds,radius,radius,imagePaint);
    }

    private void drawPlaceholder(Canvas canvas) {
        if (placeholder == null) return;
        placeholder.setBounds(0, 0, getWidth(), getHeight());
        placeholder.draw(canvas);
    }
}
