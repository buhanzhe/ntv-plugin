package xiao.bu.channel.tvbox;

import android.view.View;
import android.widget.LinearLayout;

/** Keeps film information and playback side by side where both columns fit. */
final class BoxPageLayout extends LinearLayout {
    private final BoxUi ui;
    private final View feature;
    private final View episodes;

    BoxPageLayout(BoxUi ui, View feature, View episodes) {
        super(ui.context);
        this.ui = ui;
        this.feature = feature;
        this.episodes = episodes;
        setOrientation(VERTICAL);
        setBaselineAligned(false);
        addView(feature, new LayoutParams(-1, -2));
        addView(episodes, new LayoutParams(-1, 0, 1));
    }

    @Override protected void onMeasure(int width, int height) {
        int pageWidth = MeasureSpec.getSize(width);
        int pageHeight = MeasureSpec.getSize(height);
        boolean hasEpisodes = episodes.getVisibility() != GONE;
        boolean split = hasEpisodes && pageWidth >= ui.px(850);
        setOrientation(split ? HORIZONTAL : VERTICAL);
        LayoutParams content = (LayoutParams) feature.getLayoutParams();
        LayoutParams list = (LayoutParams) episodes.getLayoutParams();
        content.width = split ? Math.min(ui.px(530), Math.round(pageWidth * .45f)) : -1;
        list.width = split ? 0 : -1;
        list.height = split ? -1 : 0;
        list.leftMargin = split ? ui.px(24) : 0;
        list.topMargin = split ? 0 : ui.px(16);
        int reserve = !split && hasEpisodes ? ui.px(158) : 0;
        int limit = Math.max(0, Math.min(ui.px(split ? 440 : 320), pageHeight - reserve));
        int measureWidth = split ? content.width : pageWidth;
        feature.measure(MeasureSpec.makeMeasureSpec(measureWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        content.height = feature.getMeasuredHeight();
        super.onMeasure(width, height);
    }
}
