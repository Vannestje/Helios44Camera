package com.someday.helios44;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** 9:16 portrait camera viewport. */
final class AspectRatioFrameLayout extends FrameLayout {
    AspectRatioFrameLayout(Context context) { super(context); }
    AspectRatioFrameLayout(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int desiredHeight = Math.round(width * 16f / 9f);
        int maxHeight = MeasureSpec.getSize(heightMeasureSpec);
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            desiredHeight = Math.min(desiredHeight, maxHeight);
        }
        setMeasuredDimension(width, desiredHeight);
        int childW = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int childH = MeasureSpec.makeMeasureSpec(desiredHeight, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childW, childH);
        }
    }
}
