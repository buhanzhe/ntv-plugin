package xiao.bu.channel.tvbox;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Draw in destination pixels: no scaled 24px path mask or bitmap is reused. */
final class BoxIcon extends Drawable {
    private final int kind;
    private final boolean stateful;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final RectF rect=new RectF();
    private float unit,left,top,stroke;
    private int alpha=255;

    BoxIcon(int kind,boolean stateful){this.kind=kind;this.stateful=stateful;}
    @Override protected void onBoundsChange(Rect bounds){
        float edge=Math.min(bounds.width(),bounds.height());unit=edge/24f;
        left=bounds.left+(bounds.width()-edge)/2;top=bounds.top+(bounds.height()-edge)/2;
        stroke=Math.max(1.5f,Math.round(unit*1.8f));
    }
    private float x(float value){return left+value*unit;}
    private float y(float value){return top+value*unit;}
    private void move(float a,float b){path.moveTo(x(a),y(b));}
    private void line(float a,float b){path.lineTo(x(a),y(b));}
    @Override public void draw(Canvas canvas){
        if(unit<=0)return;
        paint.setColor(BoxUi.TEXT);paint.setAlpha(alpha);paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);path.reset();
        if(kind==3){canvas.drawCircle(x(10.25f),y(10.25f),6.9f*unit,paint);canvas.drawLine(x(15.25f),y(15.25f),x(21),y(21),paint);}
        else if(kind==1){
            canvas.drawCircle(x(12),y(12),9.5f*unit,paint);
            for(int a=0;a<2;a++)for(int b=0;b<2;b++)canvas.drawCircle(x(8.3f+a*7.4f),y(8.3f+b*7.4f),2.25f*unit,paint);
            paint.setStyle(Paint.Style.FILL);canvas.drawCircle(x(12),y(12),.85f*unit,paint);
        }else if(kind==0){
            move(3,7);line(12,2.5f);line(21,7);line(12,11.5f);path.close();canvas.drawPath(path,paint);
            for(int row=12;row<=17;row+=5){path.reset();move(3,row);line(12,row+4.5f);line(21,row);canvas.drawPath(path,paint);}
        }else if(kind==2){
            for(int a=3;a<=14;a+=11)for(int b=3;b<=14;b+=11){rect.set(x(a),y(b),x(a+7),y(b+7));canvas.drawRoundRect(rect,2*unit,2*unit,paint);}
        }else if(kind==5){move(14.5f,4.5f);line(7,12);line(14.5f,19.5f);canvas.drawPath(path,paint);}
        else if(kind==10){move(9.5f,4.5f);line(17,12);line(9.5f,19.5f);canvas.drawPath(path,paint);}
        else if(kind==7){move(7,4);line(20,12);line(7,20);path.close();paint.setStyle(Paint.Style.FILL);canvas.drawPath(path,paint);}
        else if(kind==8){move(3,4);line(21,4);line(14,12);line(14,19);line(10,21);line(10,12);path.close();canvas.drawPath(path,paint);}
        else if(kind==9){
            move(12,2.8f);line(14.9f,8.6f);line(21.3f,9.6f);line(16.6f,14.1f);line(17.8f,20.5f);
            line(12,17.4f);line(6.2f,20.5f);line(7.4f,14.1f);line(2.7f,9.6f);line(9.1f,8.6f);
            path.close();canvas.drawPath(path,paint);
        }
        else{
            for(int i=0;i<32;i++){double angle=i*Math.PI/16;float radius=i%4==0||i%4==3?10:8.25f;float a=12+(float)Math.cos(angle)*radius,b=12+(float)Math.sin(angle)*radius;if(i==0)move(a,b);else line(a,b);}
            path.close();canvas.drawPath(path,paint);canvas.drawCircle(x(12),y(12),3.5f*unit,paint);
        }
    }
    @Override public boolean isStateful(){return stateful;}
    @Override protected boolean onStateChange(int[] state){invalidateSelf();return true;}
    @Override public void setAlpha(int value){alpha=Math.max(0,Math.min(255,value));invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
