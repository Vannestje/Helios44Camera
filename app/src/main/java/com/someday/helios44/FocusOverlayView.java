package com.someday.helios44;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

final class FocusOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float x = -1f;
    private float y = -1f;

    FocusOverlayView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(0xE6FFFFFF);
        setClickable(false);
        setFocusable(false);
    }

    void setPoint(float px, float py) {
        x = px;
        y = py;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (x < 0 || y < 0) return;
        float r = dp(24f);
        canvas.drawCircle(x, y, r, paint);
        canvas.drawLine(x - r - dp(7), y, x - r + dp(2), y, paint);
        canvas.drawLine(x + r - dp(2), y, x + r + dp(7), y, paint);
        canvas.drawLine(x, y - r - dp(7), x, y - r + dp(2), paint);
        canvas.drawLine(x, y + r - dp(2), x, y + r + dp(7), paint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
