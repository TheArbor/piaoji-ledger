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

import java.util.Locale;

/**
 * 每日支出柱状趋势图：峰值高亮、逐日生长动画。
 */
public class TrendChartView extends View {

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baselinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private long[] daily = new long[0];
    private long peak;
    private int peakIndex = -1;
    private float progress = 1f;
    private ValueAnimator animator;

    private int colorPrimary;
    private int colorContainer;
    private int colorLabels;
    private int colorBaseline;

    public TrendChartView(Context context) {
        this(context, null);
    }

    public TrendChartView(Context context, AttributeSet attrs) {
        super(context, attrs);

        colorPrimary = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorPrimary, Color.parseColor("#1F6F5C"));
        colorContainer = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorPrimaryContainer, Color.parseColor("#A8F2DA"));
        colorLabels = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnSurfaceVariant, Color.parseColor("#7A7A7A"));
        colorBaseline = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOutlineVariant, Color.parseColor("#D6D3CD"));

        baselinePaint.setColor(colorBaseline);
        baselinePaint.setStrokeWidth(dp(1));

        labelPaint.setColor(colorLabels);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(sp(9));

        valuePaint.setColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnSurface, Color.parseColor("#1B1C1A")));
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTextSize(sp(10));
        valuePaint.setFakeBoldText(true);
    }

    public void setData(long[] dailyCents) {
        daily = dailyCents == null ? new long[0] : dailyCents;
        peak = 0;
        peakIndex = -1;
        for (int i = 0; i < daily.length; i++) {
            if (daily[i] > peak) {
                peak = daily[i];
                peakIndex = i;
            }
        }

        if (animator != null) {
            animator.cancel();
        }
        progress = 0f;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(720);
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
        int count = daily.length;
        if (count == 0) {
            canvas.drawText("暂无支出数据", getWidth() / 2f, getHeight() / 2f, labelPaint);
            return;
        }

        float left = dp(6);
        float right = getWidth() - dp(6);
        float top = dp(30);
        float bottom = getHeight() - dp(18);
        float plotHeight = bottom - top;
        float slot = (right - left) / count;
        float barWidth = Math.min(slot * 0.58f, dp(10));
        float radius = barWidth / 2f;

        canvas.drawLine(left, bottom, right, bottom, baselinePaint);

        for (int i = 0; i < count; i++) {
            float centerX = left + slot * i + slot / 2f;
            float barHeight = peak == 0 ? 0 : plotHeight * daily[i] / peak * progress;
            if (daily[i] > 0) {
                barHeight = Math.max(barHeight, dp(3));
            }
            if (barHeight <= 0) {
                continue;
            }
            barPaint.setColor(i == peakIndex ? colorPrimary : colorContainer);
            rect.set(centerX - barWidth / 2f, bottom - barHeight,
                    centerX + barWidth / 2f, bottom);
            canvas.drawRoundRect(rect, radius, radius, barPaint);
        }

        // 日期刻度：1、每 5 天、最后一天
        for (int i = 0; i < count; i++) {
            int day = i + 1;
            boolean show = day == 1 || day % 5 == 0 || day == count;
            if (!show) {
                continue;
            }
            float centerX = left + slot * i + slot / 2f;
            canvas.drawText(String.valueOf(day), centerX, getHeight() - dp(4), labelPaint);
        }

        if (peak > 0 && peakIndex >= 0) {
            float centerX = left + slot * peakIndex + slot / 2f;
            String value = shortMoney(peak);
            float textWidth = valuePaint.measureText(value);
            float x = Math.max(textWidth / 2f + dp(2),
                    Math.min(centerX, getWidth() - textWidth / 2f - dp(2)));
            canvas.drawText(value, x, dp(12), valuePaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    private String shortMoney(long cents) {
        double yuan = cents / 100.0;
        if (yuan >= 10000) {
            return String.format(Locale.CHINA, "¥%.1f万", yuan / 10000);
        }
        if (yuan >= 1000) {
            return String.format(Locale.CHINA, "¥%,.0f", yuan);
        }
        return String.format(Locale.CHINA, "¥%.0f", yuan);
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
