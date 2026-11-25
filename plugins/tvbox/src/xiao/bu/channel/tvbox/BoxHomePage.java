package xiao.bu.channel.tvbox;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** The shelf stays measured while hidden, so the feature does not move when the remote wakes it. */
final class BoxHomePage extends LinearLayout {
    private static final long IDLE_MS = 5000;
    private static final long ROTATION_MS = 6000;

    static final class Item {
        final String name, facts, description, picture;
        final boolean playable;

        Item(String name, String facts, String description, String picture, boolean playable) {
            this.name = name;
            this.facts = facts;
            this.description = description;
            this.picture = picture;
            this.playable = playable;
        }
    }

    interface Actions {
        void select(int position);
        void play(int position);
        void open(int position);
    }

    private final BoxUi ui;
    private final BoxArt art;
    private final List<Item> items;
    private final Actions actions;
    private final List<Card> cards = new ArrayList<>();
    private final LinearLayout shelf, row;
    private final HorizontalScrollView rail;
    private final LinearLayout feature;
    private final TextView title, facts, description, hint;
    private final Runnable syncPictures = this::updatePictures;
    private final Runnable idle = this::hideShelf;
    private final Runnable rotate = this::rotateFeature;
    private final Runnable hold = this::playHeldCard;
    private boolean hidden, restoreRailFocus;
    private Card heldCard;
    private boolean holdPlayed;
    private int selected, stride, lastFirst = -1, lastLast = -1;

    BoxHomePage(BoxUi ui, BoxArt art, List<Item> items, int initial, Actions actions) {
        super(ui.context);
        this.ui = ui;
        this.art = art;
        this.items = items;
        this.actions = actions;
        setOrientation(VERTICAL);
        setBaselineAligned(false);

        feature = ui.vertical();
        feature.setGravity(Gravity.CENTER_VERTICAL);
        feature.setPadding(ui.px(30), 0, ui.px(12), 0);
        title = ui.displayTitle("");
        title.setTextColor(BoxUi.HOME_GOLD);
        title.setShadowLayer(ui.px(8), 0, ui.px(2), 0xa0000000);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        feature.addView(title, new LayoutParams(-1, -2));
        facts = featureLabel(22, 1);
        description = featureLabel(25, 2);
        addView(feature, new LayoutParams(-1, 0, 1));

        shelf = ui.vertical();
        LinearLayout heading = ui.horizontal();
        heading.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        heading.setPadding(ui.px(8), 0, ui.px(8), 0);
        hint = ui.text("", 20, BoxUi.SUB);
        heading.addView(hint, new LayoutParams(-2, -1));
        shelf.addView(heading, new LayoutParams(-1, ui.px(44)));

        rail = new HorizontalScrollView(ui.context) {
            @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
                super.onScrollChanged(x, y, oldX, oldY);
                queuePictures();
            }
        };
        rail.setFillViewport(false);
        rail.setHorizontalScrollBarEnabled(false);
        rail.setOverScrollMode(OVER_SCROLL_NEVER);
        rail.setFocusable(false);
        row = ui.horizontal();
        row.setGravity(Gravity.TOP);
        row.setPadding(ui.px(4), 0, ui.px(4), 0);
        rail.addView(row, new FrameLayout.LayoutParams(-2, -1));
        shelf.addView(rail, new LayoutParams(-1, -2));
        addView(shelf, new LayoutParams(-1, -2));
        for (int i = 0; i < items.size(); i++) addCard(i);
        select(initial);
    }

    private TextView featureLabel(int size, int lines) {
        TextView label = ui.text("", size, BoxUi.TEXT);
        label.setMaxLines(lines);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(ui.px(4), 0, ui.px(1), 0xaa000000);
        LayoutParams params = new LayoutParams(-1, -2);
        params.topMargin = ui.px(12);
        feature.addView(label, params);
        return label;
    }

    private void addCard(int position) {
        Item item = items.get(position);
        Card card = new Card(item);
        card.setNextFocusLeftId(position == 0 ? card.getId() : cards.get(position - 1).getId());
        card.setNextFocusRightId(card.getId());
        if (position > 0) cards.get(position - 1).setNextFocusRightId(card.getId());
        card.setOnFocusChangeListener((view, focused) -> {
            if (focused && !hidden) select(position);
        });
        card.setOnClickListener(view -> {
            if (item.playable) {
                card.requestFocusFromTouch();
                select(position);
            } else {
                actions.open(position);
            }
        });
        if (item.playable) card.setOnLongClickListener(view -> {
            select(position);
            actions.play(position);
            return true;
        });
        cards.add(card);
        row.addView(card, new LayoutParams(0, -1));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        int gap = ui.px(10);
        int usable = Math.max(1, width - row.getPaddingLeft() - row.getPaddingRight());
        int columns = Math.max(1, Math.min(5, usable / Math.max(1, ui.px(165))));
        int cardWidth = Math.max(1, (usable - (columns - 1) * gap) / columns);
        stride = cardWidth + gap;
        int cardHeight = Math.min(Math.round(cardWidth * .625f), Math.round(height * .42f)) + ui.px(38);
        rail.getLayoutParams().height = cardHeight;
        for (int i = 0; i < cards.size(); i++) {
            LayoutParams card = (LayoutParams) cards.get(i).getLayoutParams();
            card.width = cardWidth;
            card.rightMargin = i == cards.size() - 1 ? 0 : gap;
        }
        LayoutParams textArea = (LayoutParams) feature.getLayoutParams();
        textArea.width = Math.min(Math.round(width * .64f), ui.px(730));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.min(ui.px(98), Math.round(height * .18f)));
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) queuePictures();
    }

    void select(int position) {
        if (position < 0 || position >= items.size()) return;
        cards.get(selected).setSelected(false);
        selected = position;
        cards.get(selected).setSelected(true);
        Item item = items.get(selected);
        title.setText(item.name);
        facts.setText(item.facts);
        facts.setVisibility(item.facts.isEmpty() ? GONE : VISIBLE);
        description.setText(item.description);
        description.setVisibility(item.description.isEmpty() ? GONE : VISIBLE);
        hint.setText(item.playable ? "长按 OK 播放" : "");
        actions.select(selected);
    }

    int selected() {
        return selected;
    }

    boolean focusCard() {
        return !hidden && cards.get(selected).requestFocusFromTouch();
    }

    boolean confirm(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            Card card = heldCard;
            if (card == null) return false;
            if (!holdPlayed && !event.isCanceled() && card.isFocused()) card.performClick();
            cancelHold();
            return true;
        }
        Card card = cards.get(selected);
        if (event.getAction() != KeyEvent.ACTION_DOWN || hidden || !card.isFocused()) return false;
        if (event.getRepeatCount() == 0) {
            cancelHold();
            heldCard = card;
            if (card.playable) postDelayed(hold, ViewConfiguration.getLongPressTimeout());
        }
        return true;
    }

    private void playHeldCard() {
        if (heldCard != null && heldCard.isFocused() && hasWindowFocus() && isShown()) {
            holdPlayed = true;
            heldCard.performLongClick();
        }
    }

    private void cancelHold() {
        heldCard = null;
        holdPlayed = false;
        removeCallbacks(hold);
    }

    void wake(boolean remote) {
        if (hidden) {
            hidden = false;
            shelf.setVisibility(VISIBLE);
            boolean restore = remote && restoreRailFocus;
            restoreRailFocus = false;
            if (restore) focusCard();
            post(() -> {
                if (hidden || !isShown()) return;
                rail.scrollTo(cards.get(selected).getLeft(), 0);
                queuePictures();
            });
        }
        restartIdleTimer();
    }

    private void hideShelf() {
        if (!isShown() || !hasWindowFocus()) return;
        restoreRailFocus = rail.hasFocus();
        hidden = true;
        shelf.setVisibility(INVISIBLE);
        if (items.size() > 1) postDelayed(rotate, ROTATION_MS);
    }

    private void rotateFeature() {
        if (!hidden || !isShown() || !hasWindowFocus()) return;
        select((selected + 1) % items.size());
        postDelayed(rotate, ROTATION_MS);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        restartIdleTimer();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) wake(false);
        else {
            removeCallbacks(idle);
            removeCallbacks(rotate);
            cancelHold();
        }
    }

    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (shelf == null) return;
        if (isShown()) wake(false);
        else {
            removeCallbacks(idle);
            removeCallbacks(rotate);
            cancelHold();
        }
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(idle);
        removeCallbacks(rotate);
        removeCallbacks(syncPictures);
        cancelHold();
        super.onDetachedFromWindow();
    }

    private void restartIdleTimer() {
        removeCallbacks(idle);
        removeCallbacks(rotate);
        if (isShown() && hasWindowFocus()) postDelayed(idle, IDLE_MS);
    }

    private void queuePictures() {
        removeCallbacks(syncPictures);
        post(syncPictures);
    }

    private void updatePictures() {
        if (hidden || stride <= 0 || rail.getWidth() <= 0) return;
        int first = Math.max(0, rail.getScrollX() / stride - 1);
        int last = Math.min(cards.size() - 1, (rail.getScrollX() + rail.getWidth()) / stride + 1);
        if (first == lastFirst && last == lastLast) return;
        if (lastFirst >= 0) {
            for (int i = lastFirst; i <= lastLast; i++) {
                if (i < first || i > last) art.load(cards.get(i).picture, "");
            }
        }
        for (int i = first; i <= last; i++) {
            if (lastFirst < 0 || i < lastFirst || i > lastLast) art.load(cards.get(i).picture, items.get(i).picture);
        }
        lastFirst = first;
        lastLast = last;
    }

    private final class Card extends LinearLayout {
        final ImageView picture, play;
        final FrameLayout image;
        final boolean playable;
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF bounds = new RectF();

        Card(Item item) {
            super(ui.context);
            playable = item.playable;
            // The IDs only need to be unique within this page on API 14–16.
            setId(0x00b60000 + cards.size());
            setOrientation(VERTICAL);
            setFocusable(true);
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            setContentDescription(item.name);
            setPadding(ui.px(4), ui.px(4), ui.px(4), 0);
            image = new FrameLayout(ui.context);
            picture = ui.imageView();
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.setBackgroundDrawable(ui.glass(0x88314857, 20));
            image.addView(picture, new FrameLayout.LayoutParams(-1, -1));
            play = new ImageView(ui.context);
            play.setImageDrawable(ui.actionIcon(7));
            play.setPadding(ui.px(8), ui.px(8), ui.px(8), ui.px(8));
            GradientDrawable badge = ui.fill(0xba07121a, 24);
            badge.setStroke(Math.max(1, ui.px(1)), 0x99ffffff);
            play.setBackgroundDrawable(badge);
            play.setVisibility(INVISIBLE);
            FrameLayout.LayoutParams badgeSize = new FrameLayout.LayoutParams(ui.px(40), ui.px(40),
                    Gravity.LEFT | Gravity.BOTTOM);
            badgeSize.setMargins(ui.px(12), 0, 0, ui.px(10));
            image.addView(play, badgeSize);
            addView(image, new LayoutParams(-1, 0, 1));
            TextView caption = ui.text(item.name, 24, BoxUi.TEXT);
            caption.setSingleLine(true);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setPadding(ui.px(10), 0, ui.px(5), 0);
            addView(caption, new LayoutParams(-1, ui.px(34)));
        }

        @Override public void setSelected(boolean selected) {
            super.setSelected(selected);
            if (play != null) play.setVisibility(selected && playable ? VISIBLE : INVISIBLE);
            invalidate();
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (!isSelected()) return;
            bounds.set(image.getLeft(), image.getTop(), image.getRight(), image.getBottom());
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Math.max(3, ui.px(6)));
            ring.setColor(0x35ffd098);
            canvas.drawRoundRect(bounds, ui.px(17), ui.px(17), ring);
            ring.setStrokeWidth(Math.max(2, ui.px(3)));
            ring.setColor(0xfffff2dd);
            canvas.drawRoundRect(bounds, ui.px(17), ui.px(17), ring);
        }
    }
}
