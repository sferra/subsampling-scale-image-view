package com.davemorrissey.labs.subscaleview;

import android.content.Context;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.View;

abstract  class ScaleImageViewBase extends View implements DeprecatedConstants {

    protected static final String FILE_SCHEME = "file:///";
    protected static final String ASSET_SCHEME = "file:///android_asset/";

    // Source image dimensions and orientation - dimensions relate to the unrotated image
    private int sourceWidth;
    private int sourceHeight;
    protected int sourceOrientation;

    // Image orientation setting
    protected Orientation orientation = Orientation.DEGREES_0;

    public ScaleImageViewBase(Context context) {
        super(context);
    }

    public ScaleImageViewBase(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ScaleImageViewBase(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * Returns the orientation setting. This can return {@link #ORIENTATION_USE_EXIF}, in which case it doesn't tell you
     * the applied orientation of the image. For that, use {@link #getAppliedOrientation()}.
     */
    public final int getOrientation() {
        return orientation.rotationDegrees;
    }

    /**
     * Sets the image orientation. This can be freely called at any time.
     */
    public void setOrientation(final Orientation orientation) {
        if (orientation == this.orientation) {
            return;
        }
        this.orientation = orientation != null ? orientation : Orientation.DEGREES_0;
        reset(false);
        invalidate();
        requestLayout();
    }

    /**
     * Sets the image orientation. This can be freely called at any time.
     * @deprecated Use {@link #setOrientation(Orientation)} instead.
     */
    public void setOrientation(int orientation) {
        final Orientation orientationValue = Orientation.fromRotationDegrees(orientation);
        if (orientationValue == null) {
            throw new IllegalArgumentException("Invalid orientation: " + orientation);
        }
        setOrientation(orientationValue);
    }

    /**
     * Returns the actual orientation of the image relative to the source file. This will be based on the source file's
     * EXIF orientation if you're using ORIENTATION_USE_EXIF. Values are 0, 90, 180, 270.
     */
    public final int getAppliedOrientation() {
        return getRequiredRotation();
    }

    /**
     * Determines the rotation to be applied to tiles, based on EXIF orientation or chosen setting.
     */
    // TODO merge with getAppliedOrientation()
    protected int getRequiredRotation() {
        if (orientation == Orientation.EXIF) {
            return sourceOrientation;
        } else {
            return orientation.rotationDegrees;
        }
    }

    protected void setSourceSize(int sourceWidth, int sourceHeight) {
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
    }

    /**
     * Get source width, ignoring orientation. If {@link #getOrientation()} returns 90 or 270, you can use {@link #getSourceHeight()}
     * for the apparent width.
     */
    public int getSourceWidth() {
        return sourceWidth;
    }

    /**
     * Get source height, ignoring orientation. If {@link #getOrientation()} returns 90 or 270, you can use {@link #getSourceWidth()}
     * for the apparent height.
     */
    public int getSourceHeight() {
        return sourceHeight;
    }

    /**
     * Get source width, ignoring orientation. If {@link #getOrientation()} returns 90 or 270, you can use {@link #getSourceHeight()}
     * for the apparent width.
     * @deprecated Use {@link #getSourceWidth()} instead.
     */
    public final int getSWidth() {
        return getSourceWidth();
    }

    /**
     * Get source height, ignoring orientation. If {@link #getOrientation()} returns 90 or 270, you can use {@link #getSourceWidth()}
     * for the apparent height.
     * @deprecated Use {@link #getSourceHeight()} instead.
     */
    public final int getSHeight() {
        return getSourceHeight();
    }

    /**
     * Get source width taking rotation into account.
     */
    @SuppressWarnings("SuspiciousNameCombination")
    protected int sWidth() {
        int rotation = getRequiredRotation();
        if (rotation == 90 || rotation == 270) {
            return getSourceHeight();
        } else {
            return getSourceWidth();
        }
    }

    /**
     * Get source height taking rotation into account.
     */
    @SuppressWarnings("SuspiciousNameCombination")
    protected int sHeight() {
        int rotation = getRequiredRotation();
        if (rotation == 90 || rotation == 270) {
            return getSourceWidth();
        } else {
            return getSourceHeight();
        }
    }

    /**
     * Measures the width and height of the view, preserving the aspect ratio of the image displayed if wrap_content is
     * used. The image will scale within this box, not resizing the view as it is zoomed.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
        int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
        int parentHeight = MeasureSpec.getSize(heightMeasureSpec);
        boolean resizeWidth = widthSpecMode != MeasureSpec.EXACTLY;
        boolean resizeHeight = heightSpecMode != MeasureSpec.EXACTLY;
        int width = parentWidth;
        int height = parentHeight;
        if (getSourceWidth() > 0 && getSourceHeight() > 0) {
            if (resizeWidth && resizeHeight) {
                width = sWidth();
                height = sHeight();
            } else if (resizeHeight) {
                height = (int)((((double)sHeight()/(double)sWidth()) * width));
            } else if (resizeWidth) {
                width = (int)((((double)sWidth()/(double)sHeight()) * height));
            }
        }
        width = Math.max(width, getSuggestedMinimumWidth());
        height = Math.max(height, getSuggestedMinimumHeight());
        setMeasuredDimension(width, height);
    }

    protected abstract void reset(boolean isNewImage);

    protected static class Anim {

        public float scaleStart; // Scale at start of anim
        public float scaleEnd; // Scale at end of anim (target)
        public PointF sCenterStart; // Source center point at start
        public PointF sCenterEnd; // Source center point at end, adjusted for pan limits
        public PointF sCenterEndRequested; // Source center point that was requested, without adjustment
        public PointF vFocusStart; // View point that was double tapped
        public PointF vFocusEnd; // Where the view focal point should be moved to during the anim
        public long duration = 500; // How long the anim takes
        public boolean interruptible = true; // Whether the anim can be interrupted by a touch
        public int easing = EASE_IN_OUT_QUAD; // Easing style
        public long time = System.currentTimeMillis(); // Start time

    }

    protected static class ScaleAndTranslate {
        public ScaleAndTranslate(float scale, PointF vTranslate) {
            this.scale = scale;
            this.vTranslate = vTranslate;
        }
        public float scale;
        public PointF vTranslate;
    }
}
