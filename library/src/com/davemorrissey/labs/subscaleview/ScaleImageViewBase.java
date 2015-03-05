package com.davemorrissey.labs.subscaleview;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.FloatMath;
import android.view.View;

abstract  class ScaleImageViewBase extends View implements DeprecatedConstants {

    protected static final String FILE_SCHEME = "file:///";
    protected static final String ASSET_SCHEME = "file:///android_asset/";

    // Source image dimensions and orientation - dimensions relate to the unrotated image
    private int sourceWidth;
    private int sourceHeight;
    protected int sourceOrientation;

    // Screen coordinate of top-left corner of source image
    protected PointF vTranslate;
    protected PointF vTranslateStart;

    // Current scale and scale at start of zoom
    protected float scale;
    protected float scaleStart;

    // Max scale allowed (prevent infinite zoom)
    protected float maxScale = 2F;

    // Min scale allowed (prevent infinite zoom)
    private float minScale = minScale();

    // Pan limiting style
    private int panLimit = PAN_LIMIT_INSIDE;

    // Minimum scale type
    private int minimumScaleType = SCALE_TYPE_CENTER_INSIDE;

    // Source coordinate to center on, used when new position is set externally before view is ready
    protected Float pendingScale;
    protected PointF sPendingCenter;
    protected PointF sRequestedCenter;

    // Image orientation setting
    protected Orientation orientation = Orientation.DEGREES_0;

    // Scale and center animation tracking
    protected Anim anim;

    // helper
    private ScaleAndTranslate satTemp; // TODO make this field final if possible

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
    protected int rotatedSourceWidth() {
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
    protected int rotatedSourceHeight() {
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
                width = rotatedSourceWidth();
                height = rotatedSourceHeight();
            } else if (resizeHeight) {
                height = (int)((((double) rotatedSourceHeight()/(double) rotatedSourceWidth()) * width));
            } else if (resizeWidth) {
                width = (int)((((double) rotatedSourceWidth()/(double) rotatedSourceHeight()) * height));
            }
        }
        width = Math.max(width, getSuggestedMinimumWidth());
        height = Math.max(height, getSuggestedMinimumHeight());
        setMeasuredDimension(width, height);
    }

    /**
     * Adjusts current scale and translate values to keep scale within the allowed range and the image on screen. Minimum scale
     * is set so one dimension fills the view and the image is centered on the other dimension.
     * @param center Whether the image should be centered in the dimension it's too small to fill. While animating this can be false to avoid changes in direction as bounds are reached.
     */
    protected void fitToBounds(boolean center) {
        boolean init = false;
        if (vTranslate == null) {
            init = true;
            vTranslate = new PointF(0, 0);
        }
        if (satTemp == null) {
            satTemp = new ScaleAndTranslate(0, new PointF(0, 0));
        }
        satTemp.scale = scale;
        satTemp.vTranslate.set(vTranslate);
        fitToBounds(center, satTemp);
        scale = satTemp.scale;
        vTranslate.set(satTemp.vTranslate);
        if (init) {
            vTranslate.set(vTranslateForSCenter(rotatedSourceWidth()/2, rotatedSourceHeight()/2, scale));
        }
    }

    /**
     * Adjusts hypothetical future scale and translate values to keep scale within the allowed range and the image on screen. Minimum scale
     * is set so one dimension fills the view and the image is centered on the other dimension. Used to calculate what the target of an
     * animation should be.
     * @param center Whether the image should be centered in the dimension it's too small to fill. While animating this can be false to avoid changes in direction as bounds are reached.
     * @param sat The scale we want and the translation we're aiming for. The values are adjusted to be valid.
     */
    protected void fitToBounds(boolean center, ScaleAndTranslate sat) {
        if (panLimit == PAN_LIMIT_OUTSIDE && isImageReady()) {
            center = false;
        }

        PointF vTranslate = sat.vTranslate;
        float scale = limitedScale(sat.scale);
        float scaleWidth = scale * rotatedSourceWidth();
        float scaleHeight = scale * rotatedSourceHeight();

        if (panLimit == PAN_LIMIT_CENTER && isImageReady()) {
            vTranslate.x = Math.max(vTranslate.x, getWidth()/2 - scaleWidth);
            vTranslate.y = Math.max(vTranslate.y, getHeight()/2 - scaleHeight);
        } else if (center) {
            vTranslate.x = Math.max(vTranslate.x, getWidth() - scaleWidth);
            vTranslate.y = Math.max(vTranslate.y, getHeight() - scaleHeight);
        } else {
            vTranslate.x = Math.max(vTranslate.x, -scaleWidth);
            vTranslate.y = Math.max(vTranslate.y, -scaleHeight);
        }

        // Asymmetric padding adjustments
        float xPaddingRatio = getPaddingLeft() > 0 || getPaddingRight() > 0 ? getPaddingLeft()/(float)(getPaddingLeft() + getPaddingRight()) : 0.5f;
        float yPaddingRatio = getPaddingTop() > 0 || getPaddingBottom() > 0 ? getPaddingTop()/(float)(getPaddingTop() + getPaddingBottom()) : 0.5f;

        float maxTx;
        float maxTy;
        if (panLimit == PAN_LIMIT_CENTER && isImageReady()) {
            maxTx = Math.max(0, getWidth()/2);
            maxTy = Math.max(0, getHeight()/2);
        } else if (center) {
            maxTx = Math.max(0, (getWidth() - scaleWidth) * xPaddingRatio);
            maxTy = Math.max(0, (getHeight() - scaleHeight) * yPaddingRatio);
        } else {
            maxTx = Math.max(0, getWidth());
            maxTy = Math.max(0, getHeight());
        }

        vTranslate.x = Math.min(vTranslate.x, maxTx);
        vTranslate.y = Math.min(vTranslate.y, maxTy);

        sat.scale = scale;
    }
    /**
     * Set the pan limiting style. See static fields. Normally {@link #PAN_LIMIT_INSIDE} is best, for image galleries.
     */
    public final void setPanLimit(int panLimit) {
        if (!VALID_PAN_LIMITS.contains(panLimit)) {
            throw new IllegalArgumentException("Invalid pan limit: " + panLimit);
        }
        this.panLimit = panLimit;
        if (isImageReady()) {
            fitToBounds(true);
            invalidate();
        }
    }

    /**
     * Set the minimum scale type. See static fields. Normally {@link #SCALE_TYPE_CENTER_INSIDE} is best, for image galleries.
     */
    public final void setMinimumScaleType(int scaleType) {
        if (!VALID_SCALE_TYPES.contains(scaleType)) {
            throw new IllegalArgumentException("Invalid scale type: " + scaleType);
        }
        this.minimumScaleType = scaleType;
        if (isImageReady()) {
            fitToBounds(true);
            invalidate();
        }
    }

    /**
     * Set the maximum scale allowed. A value of 1 means 1:1 pixels at maximum scale. You may wish to set this according
     * to screen density - on a retina screen, 1:1 may still be too small. Consider using {@link #setMinimumDpi(int)},
     * which is density aware.
     */
    public final void setMaxScale(float maxScale) {
        this.maxScale = maxScale;
    }

    /**
     * Set the minimum scale allowed. A value of 1 means 1:1 pixels at minimum scale. You may wish to set this according
     * to screen density. Consider using {@link #setMaximumDpi(int)}, which is density aware.
     */
    public final void setMinScale(float minScale) {
        this.minScale = minScale;
    }

    /**
     * This is a screen density aware alternative to {@link #setMaxScale(float)}; it allows you to express the maximum
     * allowed scale in terms of the minimum pixel density. This avoids the problem of 1:1 scale still being
     * too small on a high density screen. A sensible starting point is 160 - the default used by this view.
     * @param dpi Source image pixel density at maximum zoom.
     */
    public final void setMinimumDpi(int dpi) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float averageDpi = (metrics.xdpi + metrics.ydpi)/2;
        setMaxScale(averageDpi/dpi);
    }

    /**
     * This is a screen density aware alternative to {@link #setMinScale(float)}; it allows you to express the minimum
     * allowed scale in terms of the maximum pixel density.
     * @param dpi Source image pixel density at minimum zoom.
     */
    public final void setMaximumDpi(int dpi) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float averageDpi = (metrics.xdpi + metrics.ydpi)/2;
        setMinScale(averageDpi/dpi);
    }

    /**
     * Returns the maximum allowed scale.
     */
    public float getMaxScale() {
        return maxScale;
    }

    /**
     * Returns the minimum allowed scale.
     */
    public final float getMinScale() {
        return minScale();
    }

    /**
     * Returns the source point at the center of the view.
     */
    public final PointF getCenter() {
        int mX = getWidth()/2;
        int mY = getHeight()/2;
        return viewToSourceCoord(mX, mY);
    }

    /**
     * Returns the current scale value.
     */
    public final float getScale() {
        return scale;
    }

    /**
     * Externally change the scale and translation of the source image. This may be used with getCenter() and getScale()
     * to restore the scale and zoom after a screen rotate.
     * @param scale New scale to set.
     * @param sCenter New source image coordinate to center on the screen, subject to boundaries.
     */
    public final void setScaleAndCenter(float scale, PointF sCenter) {
        this.anim = null;
        this.pendingScale = scale;
        this.sPendingCenter = sCenter;
        this.sRequestedCenter = sCenter;
        invalidate();
    }

    /**
     * Fully zoom out and return the image to the middle of the screen. This might be useful if you have a view pager
     * and want images to be reset when the user has moved to another page.
     */
    public final void resetScaleAndCenter() {
        this.anim = null;
        this.pendingScale = limitedScale(0);
        if (isImageReady()) {
            this.sPendingCenter = new PointF(rotatedSourceWidth()/2, rotatedSourceHeight()/2);
        } else {
            this.sPendingCenter = new PointF(0, 0);
        }
        invalidate();
    }


    /**
     * Get the translation required to place a given source coordinate at the center of the screen, with the center
     * adjusted for asymmetric padding. Accepts the desired scale as an argument, so this is independent of current
     * translate and scale. The result is fitted to bounds, putting the image point as near to the screen center as permitted.
     */
    private PointF vTranslateForSCenter(float sCenterX, float sCenterY, float scale) {
        int vxCenter = getPaddingLeft() + (getWidth() - getPaddingRight() - getPaddingLeft())/2;
        int vyCenter = getPaddingTop() + (getHeight() - getPaddingBottom() - getPaddingTop())/2;
        if (satTemp == null) {
            satTemp = new ScaleAndTranslate(0, new PointF(0, 0));
        }
        satTemp.scale = scale;
        satTemp.vTranslate.set(vxCenter - (sCenterX * scale), vyCenter - (sCenterY * scale));
        fitToBounds(true, satTemp);
        return satTemp.vTranslate;
    }

    /**
     * Given a requested source center and scale, calculate what the actual center will have to be to keep the image in
     * pan limits, keeping the requested center as near to the middle of the screen as allowed.
     */
    protected PointF limitedSCenter(float sCenterX, float sCenterY, float scale, PointF sTarget) {
        PointF vTranslate = vTranslateForSCenter(sCenterX, sCenterY, scale);
        int vxCenter = getPaddingLeft() + (getWidth() - getPaddingRight() - getPaddingLeft())/2;
        int vyCenter = getPaddingTop() + (getHeight() - getPaddingBottom() - getPaddingTop())/2;
        float sx = (vxCenter - vTranslate.x)/scale;
        float sy = (vyCenter - vTranslate.y)/scale;
        sTarget.set(sx, sy);
        return sTarget;
    }

    /**
     * Returns the minimum allowed scale.
     */
    protected float minScale() {
        int vPadding = getPaddingBottom() + getPaddingTop();
        int hPadding = getPaddingLeft() + getPaddingRight();
        if (minimumScaleType == SCALE_TYPE_CENTER_CROP) {
            return Math.max((getWidth() - hPadding) / (float) rotatedSourceWidth(), (getHeight() - vPadding) / (float) rotatedSourceHeight());
        } else if (minimumScaleType == SCALE_TYPE_CUSTOM && minScale > 0) {
            return minScale;
        } else {
            return Math.min((getWidth() - hPadding) / (float) rotatedSourceWidth(), (getHeight() - vPadding) / (float) rotatedSourceHeight());
        }
    }

    /**
     * Adjust a requested scale to be within the allowed limits.
     */
    // TODO rename to something more clear
    protected float limitedScale(float targetScale) {
        targetScale = Math.max(minScale(), targetScale);
        targetScale = Math.min(maxScale, targetScale);
        return targetScale;
    }

    /**
     * Convert screen to source x coordinate.
     */
    protected float viewToSourceX(float vx) {
        if (vTranslate == null) { return Float.NaN; }
        return (vx - vTranslate.x) / scale;
    }

    /**
     * Convert screen to source y coordinate.
     */
    protected float viewToSourceY(float vy) {
        if (vTranslate == null) { return Float.NaN; }
        return (vy - vTranslate.y) / scale;
    }

    /**
     * Convert screen coordinate to source coordinate.
     */
    public final PointF viewToSourceCoord(PointF vxy) {
        return viewToSourceCoord(vxy.x, vxy.y, new PointF());
    }

    /**
     * Convert screen coordinate to source coordinate.
     */
    public final PointF viewToSourceCoord(float vx, float vy) {
        return viewToSourceCoord(vx, vy, new PointF());
    }

    /**
     * Convert screen coordinate to source coordinate.
     */
    public final PointF viewToSourceCoord(PointF vxy, PointF sTarget) {
        return viewToSourceCoord(vxy.x, vxy.y, sTarget);
    }

    /**
     * Convert screen coordinate to source coordinate.
     */
    public final PointF viewToSourceCoord(float vx, float vy, PointF sTarget) {
        if (vTranslate == null) {
            return null;
        }
        sTarget.set(viewToSourceX(vx), viewToSourceY(vy));
        return sTarget;
    }

    /**
     * Convert source to screen x coordinate.
     */
    protected float sourceToViewX(float sx) {
        if (vTranslate == null) { return Float.NaN; }
        return (sx * scale) + vTranslate.x;
    }

    /**
     * Convert source to screen y coordinate.
     */
    protected float sourceToViewY(float sy) {
        if (vTranslate == null) { return Float.NaN; }
        return (sy * scale) + vTranslate.y;
    }

    /**
     * Convert source coordinate to screen coordinate.
     */
    public final PointF sourceToViewCoord(PointF sxy) {
        return sourceToViewCoord(sxy.x, sxy.y, new PointF());
    }

    /**
     * Convert source coordinate to screen coordinate.
     */
    public final PointF sourceToViewCoord(float sx, float sy) {
        return sourceToViewCoord(sx, sy, new PointF());
    }

    /**
     * Convert source coordinate to screen coordinate.
     */
    public final PointF sourceToViewCoord(PointF sxy, PointF vTarget) {
        return sourceToViewCoord(sxy.x, sxy.y, vTarget);
    }

    /**
     * Convert source coordinate to screen coordinate.
     */
    public final PointF sourceToViewCoord(float sx, float sy, PointF vTarget) {
        if (vTranslate == null) {
            return null;
        }
        vTarget.set(sourceToViewX(sx), sourceToViewY(sy));
        return vTarget;
    }

    /**
     * Convert source rect to screen rect, integer values.
     */
    protected Rect sourceToViewRect(Rect sRect, Rect vTarget) {
        vTarget.set(
                (int)sourceToViewX(sRect.left),
                (int)sourceToViewY(sRect.top),
                (int)sourceToViewX(sRect.right),
                (int)sourceToViewY(sRect.bottom)
        );
        return vTarget;
    }

    /**
     * Get the current state of the view (scale, center, orientation) for restoration after rotate. Will return null if
     * the view is not ready.
     */
    public ImageViewState getState() {
        if (vTranslate != null && getSourceWidth() > 0 && getSourceHeight() > 0) {
            return new ImageViewState(getScale(), getCenter(), orientation);
        }
        return null;
    }

    /**
     * Set scale, center and orientation from saved state.
     */
    protected void restoreState(ImageViewState state) {
        if (state != null && state.getCenter() != null) {
            this.orientation = state.getOrientation();
            this.pendingScale = state.getScale();
            this.sPendingCenter = state.getCenter();
            invalidate();
        }
    }

    protected void reset(boolean isNewImage) {
        pendingScale = 0f;
        sPendingCenter = null;
        sRequestedCenter = null;
        anim = null;
        satTemp = null;
    }

    /**
     * Pythagoras distance between two points.
     */
    protected static float distance(float x0, float x1, float y0, float y1) {
        float x = x0 - x1;
        float y = y0 - y1;
        return FloatMath.sqrt(x * x + y * y);
    }

    /**
     * Call to find whether the view is initialised and ready for rendering.
     */
    public abstract boolean isImageReady();

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
