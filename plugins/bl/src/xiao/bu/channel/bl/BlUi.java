package xiao.bu.channel.bl;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;

/** Shared geometry and focus styling for the native BL pages. */
public class BlUi {
    public static final int TEXT=0xffeeeeee,SUB=0xffcccccc,ACCENT=0xffbb86fc;
    public final Context context;final float scale;
    BlTheme theme=BlTheme.BLACK;
    public BlUi(Context c){context=c;int width=Math.max(c.getResources().getDisplayMetrics().widthPixels,c.getResources().getDisplayMetrics().heightPixels);scale=Math.max(.5f,width/1280f);}
    public int px(float n){return Math.round(n*scale);}
    public TextView text(String text,int size,int color){TextView t=new TextView(context);t.setText(text);t.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(size));t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);return t;}
    public GradientDrawable fill(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(px(radius));return d;}
    GradientDrawable backdrop(BlTheme palette,int radius){
        if(palette!=BlTheme.CLASSIC)return fill(palette.background,radius);
        GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{palette.background,0xff54233e,0xff943c55});
        gradient.setDither(true);
        gradient.setCornerRadius(px(radius));
        return gradient;
    }
    public StateListDrawable focus(int normal){
        StateListDrawable states=new StateListDrawable();
        GradientDrawable focused=fill(theme==BlTheme.BLACK?0xff695082:theme.tint(58),10);
        focused.setStroke(px(2),theme.accent);
        states.addState(new int[]{android.R.attr.state_focused},focused);
        states.addState(new int[]{android.R.attr.state_pressed},fill(theme==BlTheme.BLACK?0xff745395:theme.tint(72),10));
        states.addState(new int[]{android.R.attr.state_selected},fill(theme==BlTheme.BLACK?0xff55415e:theme.tint(40),10));
        states.addState(new int[]{},fill(normal,10));
        return states;
    }
    int buttonColor(){return theme==BlTheme.BLACK?0x66333333:theme.tint(15);}
    void style(Button button){button.setTextColor(theme.text);button.setBackgroundDrawable(focus(buttonColor()));}
    public Button button(String text){Button b=new Button(context);b.setText(text);b.setTextSize(TypedValue.COMPLEX_UNIT_PX,px(18));style(b);b.setPadding(px(16),px(8),px(16),px(8));b.setMinHeight(px(44));b.setMinimumHeight(px(44));b.setMinWidth(0);b.setMinimumWidth(0);b.setSingleLine(true);return b;}
    public LinearLayout vertical(){LinearLayout l=new LinearLayout(context);l.setOrientation(LinearLayout.VERTICAL);return l;}
    public LinearLayout horizontal(){LinearLayout l=new LinearLayout(context);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    public void gap(LinearLayout parent,int width){parent.addView(new View(context),new LinearLayout.LayoutParams(px(width),1));}
    ScrollView scroll(View child){ScrollView s=new ScrollView(context);s.setFillViewport(true);s.addView(child);return s;}
    FrameLayout.LayoutParams frame(int w,int h,int gravity){return new FrameLayout.LayoutParams(w<0?w:px(w),h<0?h:px(h),gravity);}
    public TextView title(String value){TextView t=text(value,26,theme.text);t.setTypeface(null,Typeface.BOLD);return t;}
    public EditText editor(String hint, String value) {
        EditText input = new EditText(context);
        input.setTextColor(TEXT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(21));
        input.setSingleLine();
        input.setHint(hint);
        input.setHintTextColor(0xffaaaaaa);
        input.setPadding(px(14), 0, px(14), 0);
        input.setBackgroundDrawable(focus(0x55303030));
        input.setText(value);
        return input;
    }

    public GridView grid(int columns) {
        GridView grid = new GridView(context);
        grid.setNumColumns(columns);
        grid.setHorizontalSpacing(px(8));
        grid.setVerticalSpacing(px(8));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setColumnWidth(px(240));
        grid.setClipToPadding(false);
        grid.setPadding(0, px(18), 0, px(14));
        GradientDrawable selector = fill(0x11bb86fc, 10);
        selector.setStroke(px(2), ACCENT);
        grid.setSelector(selector);
        grid.setDrawSelectorOnTop(true);
        return grid;
    }

    public GridView keyboard(EditText input) {
        String keys = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        GridView keyboard = new GridView(context);
        keyboard.setNumColumns(10);
        keyboard.setAdapter(new BaseAdapter() {
            public int getCount() { return keys.length() + 2; }
            public Object getItem(int index) {
                if (index < keys.length()) return String.valueOf(keys.charAt(index));
                return index == keys.length() ? "删除" : "空格";
            }
            public long getItemId(int index) { return index; }
            public View getView(int index, View reusable, ViewGroup parent) {
                TextView key = reusable instanceof TextView
                        ? (TextView) reusable : text("", 23, TEXT);
                key.setText((String) getItem(index));
                key.setGravity(Gravity.CENTER);
                key.setPadding(0, px(16), 0, px(16));
                key.setBackgroundDrawable(focus(0x44222222));
                return key;
            }
        });
        keyboard.setOnItemClickListener((parent, view, index, id) -> {
            String value = input.getText().toString();
            if (index < keys.length()) value += keys.charAt(index);
            else if (index > keys.length()) value += " ";
            else if (!value.isEmpty()) value = value.substring(0, value.offsetByCodePoints(value.length(), -1));
            input.setText(value);
            input.setSelection(value.length());
        });
        return keyboard;
    }

    public static String plain(String value){return value.replaceAll("<[^>]+>","").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"");}
    static String count(long number){if(number>=100000000)return String.format(java.util.Locale.US,"%.1f亿",number/100000000d);if(number>=10000)return String.format(java.util.Locale.US,"%.1f万",number/10000d);return ""+number;}
    static String time(long ms){long sec=Math.max(0,ms/1000);return sec>=3600?String.format(java.util.Locale.US,"%d:%02d:%02d",sec/3600,sec/60%60,sec%60):String.format(java.util.Locale.US,"%02d:%02d",sec/60,sec%60);}
}
