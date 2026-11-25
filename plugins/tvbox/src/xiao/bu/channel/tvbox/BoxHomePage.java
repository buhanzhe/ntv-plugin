package xiao.bu.channel.tvbox;

import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

final class BoxHomePage extends FrameLayout {
    private static final long ROTATION_MS = 6000;

    static final class Item {
        final String name, facts, description;
        final boolean playable;

        Item(String name, String facts, String description, boolean playable) {
            this.name = name;
            this.facts = facts;
            this.description = description;
            this.playable = playable;
        }
    }

    interface Actions {
        void select(int position);
        void play(int position);
        void open(int position);
    }

    private final BoxUi ui;
    private final List<Item> items;
    private final Actions actions;
    private final LinearLayout feature;
    private final TextView title, facts, description, hint;
    private final Runnable rotate = () -> advance(1);
    private final Runnable hold = this::playHeldItem;
    private int selected;
    private int heldPosition = -1;

    BoxHomePage(BoxUi ui, List<Item> items, int initial, Actions actions) {
        super(ui.context);
        this.ui = ui;
        this.items = items;
        this.actions = actions;
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setOnClickListener(view -> {
            focusFeature();
            if (!items.get(selected).playable) actions.open(selected);
            restartRotation();
        });
        setOnLongClickListener(view -> {
            if (!items.get(selected).playable) return false;
            actions.play(selected);
            return true;
        });
        setFocusable(true);

        feature = ui.vertical();
        title = ui.displayTitle("");
        title.setTextColor(BoxUi.HOME_GOLD);
        title.setShadowLayer(ui.px(8), 0, ui.px(2), 0xa0000000);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        feature.addView(title, new LinearLayout.LayoutParams(-1, -2));
        facts = featureLabel(22, 1, BoxUi.TEXT, 12);
        description = featureLabel(25, 2, BoxUi.TEXT, 12);
        hint = featureLabel(18, 1, BoxUi.SUB, 24);

        LayoutParams featureSize = new LayoutParams(-2, -2, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        featureSize.leftMargin = ui.px(items.size() > 1 ? 80 : 30);
        addView(feature, featureSize);
        if (items.size() > 1) {
            addArrow(-1, 5, Gravity.LEFT, "上一项");
            addArrow(1, 10, Gravity.RIGHT, "下一项");
        }
        select(initial);
    }

    private TextView featureLabel(int size, int lines, int color, int spacing) {
        TextView label = ui.text("", size, color);
        label.setMaxLines(lines);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(ui.px(4), 0, ui.px(1), 0xaa000000);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = ui.px(spacing);
        feature.addView(label, params);
        return label;
    }

    private void addArrow(int direction, int icon, int gravity, String label) {
        ImageButton arrow = new ImageButton(ui.context);
        arrow.setImageDrawable(ui.navigationIcon(icon));
        int padding = ui.px(11);
        arrow.setPadding(padding, padding, padding, padding);
        arrow.setBackgroundDrawable(ui.capsuleGlass(0x9914222a));
        arrow.setContentDescription(label);
        arrow.setOnClickListener(view -> {
            focusFeature();
            advance(direction);
        });
        arrow.setFocusable(false);
        int size = ui.px(48);
        addView(arrow, new LayoutParams(size, size, gravity | Gravity.CENTER_VERTICAL));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        LayoutParams textArea = (LayoutParams) feature.getLayoutParams();
        textArea.width = Math.min(Math.round(width * .60f), ui.px(740));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.min(ui.px(98), Math.round(height * .17f)));
        super.onMeasure(widthSpec, heightSpec);
    }

    private void select(int position) {
        selected = position;
        Item item = items.get(position);
        title.setText(item.name);
        facts.setText(item.facts);
        facts.setVisibility(item.facts.isEmpty() ? GONE : VISIBLE);
        description.setText(item.description);
        description.setVisibility(item.description.isEmpty() ? GONE : VISIBLE);
        hint.setText(item.playable ? "长按 OK 播放" : "按 OK 打开");
        setContentDescription(item.name + "，" + hint.getText());
        actions.select(position);
    }

    int selected() {
        return selected;
    }

    boolean focusFeature() {
        return requestFocusFromTouch();
    }

    boolean handleKey(KeyEvent event) {
        if (!isFocused()) return false;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                cancelHold();
                advance(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            }
            return true;
        }
        if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            heldPosition = selected;
            removeCallbacks(rotate);
            if (items.get(selected).playable) postDelayed(hold, ViewConfiguration.getLongPressTimeout());
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean click = heldPosition == selected && !event.isCanceled();
            cancelHold();
            if (click) performClick();
            restartRotation();
        }
        return true;
    }

    private void playHeldItem() {
        if (heldPosition == selected && isFocused() && isShown() && hasWindowFocus()) {
            cancelHold();
            performLongClick();
        }
    }

    private void cancelHold() {
        heldPosition = -1;
        removeCallbacks(hold);
    }

    private void advance(int direction) {
        int count = items.size();
        if (count > 1 && isShown() && hasWindowFocus()) select((selected + direction + count) % count);
        restartRotation();
    }

    void restartRotation() {
        removeCallbacks(rotate);
        if (items.size() > 1 && heldPosition < 0 && isShown() && hasWindowFocus()) {
            postDelayed(rotate, ROTATION_MS);
        }
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) cancelHold();
        restartRotation();
    }

    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (items == null) return;
        if (!isShown()) cancelHold();
        restartRotation();
    }

    @Override protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        if (!focused && heldPosition >= 0) {
            cancelHold();
            restartRotation();
        }
    }

    @Override protected void onDetachedFromWindow() {
        cancelHold();
        removeCallbacks(rotate);
        super.onDetachedFromWindow();
    }
}
