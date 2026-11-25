package xiao.bu.channel.tvbox;

import android.view.View;
import android.widget.LinearLayout;

/** Reserves real content space before measuring the feature, including at short TV aspect ratios. */
final class BoxPageLayout extends LinearLayout {
    private final BoxUi ui;
    private final View feature;
    BoxPageLayout(BoxUi ui,View feature,View episodes){
        super(ui.context);this.ui=ui;this.feature=feature;
        setOrientation(VERTICAL);setBaselineAligned(false);
        addView(feature,new LayoutParams(-1,0));
        LayoutParams list=new LayoutParams(-1,0,1);list.topMargin=ui.px(24);addView(episodes,list);
    }
    @Override protected void onMeasure(int width,int height){
        int available = MeasureSpec.getSize(height);
        feature.getLayoutParams().height = Math.min(ui.px(300),
                Math.max(0, Math.min(Math.round(available * .52f), available - ui.px(220))));
        super.onMeasure(width,height);
    }
}
