package com.demo.ledger.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 分类占比环形图：跟随 Material You 取色，带扫过与百分比滚动动画。
 */
public class DonutView extends View {

    public static class Slice {
        public final String label;
        public final long cents;
        public final int color;

        public Slice(String label, long cents, int color) {
            this.label = label;
            this.cents = cents;
            this.color = color;
        }
    }

    private static final int[] PALETTE_ATTRS = {
            com.google.android.material.R.attr.colorPrimary,
            com.google.android.material.R.attr.colorTertiary,
            com.google.android.material.R.attr.colorSecondary,
            com.google.android.material.R.attr.colorPrimaryContainer,
            com.google.android.material.R.attr.colorTertiaryContainer,
            com.google.android.material.R.attr.colorSecondaryContainer,
            com.google.android.material.R.attr.colorError,
            com.google.android.material.R.attr.colorOutline
    };

    private static final int[] FALLBACK = {
            Color.parseColor("#1F6F5C"), Color.parseColor("#E0A11B"), Color.parseColor("#5B8DEF"),
            Color.parseColor("#D96C6C"), Color.parseColor("#8C6BB1"), Color.parseColor("#4DA1A9"),
            Color.parseColor("#C97B3C"), Color.parseColor("#9AA0A6")
    };

    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint amountPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private final int[] palette;

    private List<Slice> slices = new ArrayList<>();
    private long total;
    private float progress = 1f;
    private ValueAnimator animator;
    private int trackColor;
    private String topLabel = "";
    private int topPercent;

    public DonutView(Context context) {
        this(context, null);
    }

    public DonutView(Context context, AttributeSet attrs) {
        super(context, attrs);

        palette = colors(context);
        trackColor = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorSurfaceVariant, Color.parseColor("#E6E3DD"));

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(dp(18));
        arcPaint.setStrokeCap(Paint.Cap.BUTT);

        amountPaint.setColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnSurface, Color.parseColor("#1B1C1A")));
        amountPaint.setTextAlign(Paint.Align.CENTER);
        amountPaint.setTextSize(sp(24));
        amountPaint.setFakeBoldText(true);

        labelPaint.setColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, Color.parseColor("#7A7A7A")));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(sp(11));
    }

    /**
     * 跟随 Material You 动态取色的分类配色。
     */
    public static int[] colors(Context context) {
        int[] result = new int[PALETTE_ATTRS.length];
        for (int i = 0; i < PALETTE_ATTRS.length; i++) {
            result[i] = MaterialColors.getColor(context, PALETTE_ATTRS[i], FALLBACK[i]);
        }
        return result;
    }

    public int[] palette() {
        return palette;
    }

    public void setSlices(List<Slice> data) {
        slices = data == null ? new ArrayList<>() : data;
        total = 0;
        Slice top = null;
        for (Slice slice : slices) {
            total += slice.cents;
            if (slice.cents > 0 && (top == null || slice.cents > top.cents)) {
                top = slice;
            }
        }
        topLabel = top == null ? "" : top.label;
        topPercent = top == null || total <= 0
                ? 0 : (int) Math.round(top.cents * 100.0 / total);

        if (animator != null) {
            animator.cancel();
        }
        if (total <= 0) {
            progress = 1f;
            invalidate();
            return;
        }

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(760);
        animator.setInterpolator(new DecelerateInterpolator(1.8f));
        animator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) / 2f - arcPaint.getStrokeWidth() / 2f - dp(4);
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius);

        String centerValue;
        String centerLabel;
        if (total <= 0) {
            arcPaint.setColor(trackColor);
            canvas.drawArc(oval, 0, 360, false, arcPaint);
            centerValue = "—";
            centerLabel = "暂无支出";
        } else if (slices.size() == 1) {
            arcPaint.setColor(slices.get(0).color);
            canvas.drawArc(oval, -90, 360 * progress, false, arcPaint);
            centerValue = String.format(Locale.CHINA, "%d%%", Math.round(topPercent * progress));
            centerLabel = topLabel;
        } else {
            float start = -90f;
            for (Slice slice : slices) {
                float sweep = 360f * slice.cents * progress / total;
                arcPaint.setColor(slice.color);
                if (sweep > 3f) {
                    canvas.drawArc(oval, start + 1f, sweep - 2f, false, arcPaint);
                } else if (sweep > 0f) {
                    canvas.drawArc(oval, start, sweep, false, arcPaint);
                }
                start += sweep;
            }
            centerValue = String.format(Locale.CHINA, "%d%%", Math.round(topPercent * progress));
            centerLabel = topLabel;
        }

        canvas.drawText(centerValue, cx, cy + amountPaint.getTextSize() / 3f, amountPaint);
        canvas.drawText(centerLabel, cx, cy - amountPaint.getTextSize() * 0.85f, labelPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }
}
