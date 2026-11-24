package xiao.bu.channel.tvbox;

import android.view.View;
import android.widget.GridView;
import android.widget.LinearLayout;

/** Reserves real content space before measuring the feature, including at short TV aspect ratios. */
final class BoxPageLayout extends LinearLayout {
    private final BoxUi ui;
    private final View feature;
    private final GridView shelf;
    BoxPageLayout(BoxUi ui,View feature,View section,GridView shelf){
        super(ui.context);this.ui=ui;this.feature=feature;this.shelf=shelf;
        setOrientation(VERTICAL);setBaselineAligned(false);
        if(feature!=null)addView(feature,new LayoutParams(-1,0));
        LayoutParams heading=new LayoutParams(-1,ui.px(44));heading.topMargin=ui.px(feature==null?0:24);heading.bottomMargin=ui.px(8);addView(section,heading);
        shelf.setPadding(ui.px(4),ui.px(8),ui.px(4),ui.px(16));
        shelf.setHorizontalSpacing(ui.px(24));addView(shelf,new LayoutParams(-1,0,1));
    }
    BoxPageLayout(BoxUi ui,View feature,View episodes){
        super(ui.context);this.ui=ui;this.feature=feature;shelf=null;
        setOrientation(VERTICAL);setBaselineAligned(false);
        addView(feature,new LayoutParams(-1,0));
        LayoutParams list=new LayoutParams(-1,0,1);list.topMargin=ui.px(24);addView(episodes,list);
    }
    @Override protected void onMeasure(int width,int height){
        int available=MeasureSpec.getSize(height),wide=MeasureSpec.getSize(width);
        if(available>0&&wide>0&&feature!=null){
            int featureHeight;
            if(shelf!=null){
                int columns=Math.max(1,Math.min(4,wide/ui.px(210)));
                int tileWidth=(wide-shelf.getPaddingLeft()-shelf.getPaddingRight()-(columns-1)*ui.px(24))/columns;
                int row=BoxTile.landscapeHeight(ui,tileWidth)+shelf.getPaddingTop()+shelf.getPaddingBottom();
                int section=ui.px(44+24+8);
                featureHeight=Math.min(ui.px(400),Math.max(0,available-section-row));
                shelf.getLayoutParams().height=row;((LayoutParams)shelf.getLayoutParams()).weight=0;
            }else featureHeight=Math.min(ui.px(300),Math.max(0,Math.min(Math.round(available*.52f),available-ui.px(220))));
            feature.getLayoutParams().height=featureHeight;
        }
        super.onMeasure(width,height);
    }
}
