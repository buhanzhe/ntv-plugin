package xiao.bu.channel.bl;

import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A recycled BL cover card. Each channel supplies its own metadata and artwork. */
public class BlTile extends LinearLayout {
    private final FrameLayout image;
    private final ImageView cover;
    private final TextView badge;
    private final TextView title;
    private final TextView subtitle;

    public BlTile(BlUi ui) {
        super(ui.context);
        setOrientation(VERTICAL);
        setPadding(ui.px(10), ui.px(12), ui.px(10), ui.px(12));
        setBackgroundDrawable(ui.focus(0));
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        image = new FrameLayout(ui.context);
        cover = ui.imageView();
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        badge = ui.text("", 14, BlUi.TEXT);
        badge.setGravity(Gravity.BOTTOM | Gravity.LEFT);
        badge.setPadding(ui.px(8), ui.px(12), ui.px(8), ui.px(8));
        badge.setBackgroundDrawable(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xcc000000}));
        image.addView(badge, new FrameLayout.LayoutParams(-1, ui.px(40), Gravity.BOTTOM));
        addView(image, new LinearLayout.LayoutParams(-1, ui.px(130)));
        title = ui.text("", 19, BlUi.TEXT);
        title.setMinLines(2);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, ui.px(52));
        titleParams.topMargin = ui.px(8);
        addView(title, titleParams);
        subtitle = ui.text("", 14, BlUi.SUB);
        subtitle.setSingleLine();
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        addView(subtitle, new LinearLayout.LayoutParams(-1, ui.px(24)));
    }

    public ImageView bind(String name, String secondary, String label) {
        title.setText(name);
        subtitle.setText(secondary);
        badge.setText(label);
        badge.setVisibility(label.isEmpty() ? GONE : VISIBLE);
        setContentDescription(name);
        return cover;
    }

    @Override protected void onSizeChanged(int width, int height, int previousWidth, int previousHeight) {
        super.onSizeChanged(width, height, previousWidth, previousHeight);
        int coverHeight = Math.max(1, (width - getPaddingLeft() - getPaddingRight()) * 9 / 16);
        if (image.getLayoutParams().height != coverHeight) {
            image.getLayoutParams().height = coverHeight;
            image.requestLayout();
        }
    }
}
