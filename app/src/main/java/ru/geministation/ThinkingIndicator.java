package ru.geministation;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Small Material-style indeterminate circular indicator around the Gemini mark. */
final class ThinkingIndicator extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final float stroke;
    private ValueAnimator animator;
    private float phase;

    ThinkingIndicator(Context context) {
        super(context);
        stroke = 2.5f * getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(View.INVISIBLE);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float left = (getWidth() - size) / 2f + stroke / 2f;
        float top = (getHeight() - size) / 2f + stroke / 2f;
        bounds.set(left, top, left + size - stroke, top + size - stroke);
        paint.setColor(Color.rgb(77, 70, 88));
        canvas.drawArc(bounds, 0, 360, false, paint);
        paint.setColor(Color.rgb(208, 188, 255));
        float sweep = (float) (40 + 210 * (0.5 - 0.5 * Math.cos(phase * Math.PI * 2)));
        canvas.drawArc(bounds, -90 + phase * 720, sweep, false, paint);
    }
    void start() {
        if (animator != null) return;
        setVisibility(View.VISIBLE);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1800);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(value -> { phase = (float) value.getAnimatedValue(); invalidate(); });
        animator.start();
    }
    void stop() {
        if (animator != null) { animator.cancel(); animator = null; }
        phase = 0;
        setVisibility(View.INVISIBLE);
    }
    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
