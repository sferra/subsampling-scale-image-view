package com.davemorrissey.labs.subscaleview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.FloatMath;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

public abstract  class ScaleImageViewBase<ImageDataSource> extends View implements DeprecatedConstants {

    protected static final String FILE_SCHEME = "file:///";
    protected static final String ASSET_SCHEME = "file:///android_asset/";

    protected ImageDataSource imageDataSource;

    // Overlay bitmap and state info
    private boolean debug = false;
    private boolean imageSourceAvailable;

    // Source image dimensions and orientation - dimensions relate to the unrotated image
    private int sourceWidth;
    private int sourceHeight;
    protected int sourceOrientation;

    // Screen coordinate of top-left corner of source image
    protected final PointF vTranslate = new PointF();
    protected final PointF vTranslateStart = new PointF();

    // Current scale and scale at start of zoom
    protected float scale;
    protected float scaleStart;

    // Max scale allowed (prevent infinite zoom)
    protected float maxScale = 2F;

    // Min scale allowed (prevent infinite zoom)
    private float minScale;

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

    // Gesture detection settings
    private boolean panEnabled = true;
    private boolean zoomEnabled = true;
    private boolean quickScaleEnabled = true;

    // Double tap zoom behaviour
    private float doubleTapZoomScale = 1F;
    private int doubleTapZoomStyle = ZOOM_FOCUS_FIXED;

    // Is two-finger zooming in progress
    private boolean isZooming;
    // Is one-finger panning in progress
    private boolean isPanning;
    // Is quick-scale gesture in progress
    private boolean isQuickScaling;
    // Max touches used in current gesture
    private int maxTouchCount;

    // Fling detector
    private GestureDetector detector;

    // Current quickscale state
    private final float quickScaleThreshold;
    private PointF quickScaleCenter;
    private float quickScaleLastDistance;
    private PointF quickScaleLastPoint;
    private boolean quickScaleMoved;

    // Scale and center animation tracking
    protected Anim anim;

    private OnLongClickListener onLongClickListener;
    private ImageSizeDecoderListener imageSizeListener;

    // Long click handler
    private Handler handler;
    private static final int MESSAGE_LONG_CLICK = 1;

    private Paint bitmapPaint;
    private Paint debugPaint;
    private Paint backgroundPaint;

    // helper
    private final ScaleAndTranslate satTemp = new ScaleAndTranslate(0f, 0f, 0f);

    // Debug values
    private PointF vCenterStart;
    private float vDistStart;

    public ScaleImageViewBase(Context context) {
        this(context, null);
    }

    public ScaleImageViewBase(Context context, AttributeSet attrs) {
        super(context, attrs);
        minScale = minScale();
        setMinimumDpi(160);
        setDoubleTapZoomDpi(160);
        setGestureDetector(context);
        this.handler = new Handler(new Handler.Callback() {
            public boolean handleMessage(Message message) {
                if (message.what == MESSAGE_LONG_CLICK && onLongClickListener != null) {
                    maxTouchCount = 0;
                    ScaleImageViewBase.super.setOnLongClickListener(onLongClickListener);
                    performLongClick();
                    ScaleImageViewBase.super.setOnLongClickListener(null);
                }
                return true;
            }
        });
        quickScaleThreshold = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, context.getResources().getDisplayMetrics());
    }

    public void setImageSizeDecoderListener(ImageSizeDecoderListener listener) {
        this.imageSizeListener = listener;
    }

    public ImageSizeDecoderListener getImageSizeDecoderListener() {
        return imageSizeListener;
    }

    /**
     * Creates Paint objects once when first needed.
     */
    private void createPaints() {
        if (bitmapPaint == null) {
            bitmapPaint = new Paint();
            bitmapPaint.setAntiAlias(true);
            bitmapPaint.setFilterBitmap(true);
            bitmapPaint.setDither(true);
        }
        if (debugPaint == null && debug) {
            debugPaint = new Paint();
            debugPaint.setTextSize(18);
            debugPaint.setColor(Color.MAGENTA);
            debugPaint.setStyle(Paint.Style.STROKE);
        }
    }

    protected Paint getBitmapPaint() {
        return bitmapPaint;
    }

    protected Paint getDebugPaint() {
        return debugPaint;
    }

    protected Paint getBackgroundPaint() {
        return backgroundPaint;
    }

    /**
     * Set a color to render the background with.
     * @param backgroundColor The color value.
     */
    public void setBackgroundColor(int backgroundColor) {
        if (Color.alpha(backgroundColor) == 0) {
            backgroundPaint = null;
        } else {
            backgroundPaint = new Paint();
            backgroundPaint.setStyle(Paint.Style.FILL);
            backgroundPaint.setColor(backgroundColor);
        }
        invalidate();
    }

    /**
     * Set a solid color to render behind tiles, useful for displaying transparent PNGs.
     * @param tileBgColor Background color for tiles.
     * @deprecated Use {@link #setBackgroundColor(int)} instead.
     */
    public final void setTileBackgroundColor(int tileBgColor) {
        setBackgroundColor(tileBgColor);
    }

    /**
     * Returns true if zoom gesture detection is enabled.
     */
    public final boolean isZoomEnabled() {
        return zoomEnabled;
    }

    /**
     * Enable or disable zoom gesture detection. Disabling zoom locks the the current scale.
     */
    public final void setZoomEnabled(boolean zoomEnabled) {
        this.zoomEnabled = zoomEnabled;
    }

    /**
     * Returns true if pan gesture detection is enabled.
     */
    public final boolean isPanEnabled() {
        return panEnabled;
    }

    /**
     * Enable or disable pan gesture detection. Disabling pan causes the image to be centered.
     */
    public final void setPanEnabled(boolean panEnabled) {
        this.panEnabled = panEnabled;
        if (!panEnabled) {
            vTranslate.x = (getWidth()/2) - (scale * (rotatedSourceWidth()/2));
            vTranslate.y = (getHeight()/2) - (scale * (rotatedSourceHeight()/2));
            if (isImageReady()) {
                refreshImageData(true);
                invalidate();
            }
        }
    }

    /**
     * Returns true if double tap & swipe to zoom is enabled.
     */
    public final boolean isQuickScaleEnabled() {
        return quickScaleEnabled;
    }

    /**
     * Enable or disable double tap & swipe to zoom.
     */
    public final void setQuickScaleEnabled(boolean quickScaleEnabled) {
        this.quickScaleEnabled = zoomEnabled; // TODO this here is a bug
    }

    /**
     * Enables visual debugging, showing bitmap and state details.
     */
    public final void setDebug(boolean debug) {
        setDebugEnabled(debug);
    }

    public void setDebugEnabled(boolean enabled) {
        debug = enabled;
    }

    public boolean isDebugEnabled() {
        return debug;
    }

    /**
     * Set the scale the image will zoom in to when double tapped. This also the scale point where a double tap is interpreted
     * as a zoom out gesture - if the scale is greater than 90% of this value, a double tap zooms out. Avoid using values
     * greater than the max zoom.
     * @param doubleTapZoomScale New value for double tap gesture zoom scale.
     */
    public final void setDoubleTapZoomScale(float doubleTapZoomScale) {
        this.doubleTapZoomScale = doubleTapZoomScale;
    }

    /**
     * A density aware alternative to {@link #setDoubleTapZoomScale(float)}; this allows you to express the scale the
     * image will zoom in to when double tapped in terms of the image pixel density. Values lower than the max scale will
     * be ignored. A sensible starting point is 160 - the default used by this view.
     * @param dpi New value for double tap gesture zoom scale.
     */
    public final void setDoubleTapZoomDpi(int dpi) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float averageDpi = (metrics.xdpi + metrics.ydpi)/2;
        setDoubleTapZoomScale(averageDpi/dpi);
    }

    /**
     * Set the type of zoom animation to be used for double taps. See static fields.
     * @param doubleTapZoomStyle New value for zoom style.
     */
    public final void setDoubleTapZoomStyle(int doubleTapZoomStyle) {
        if (!VALID_ZOOM_STYLES.contains(doubleTapZoomStyle)) {
            throw new IllegalArgumentException("Invalid zoom style: " + doubleTapZoomStyle);
        }
        this.doubleTapZoomStyle = doubleTapZoomStyle;
    }

    protected void setGestureDetector(final Context context) {
        this.detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (panEnabled && imageSourceAvailable && vTranslate != null && e1 != null && e2 != null && (Math.abs(e1.getX() - e2.getX()) > 50 || Math.abs(e1.getY() - e2.getY()) > 50) && (Math.abs(velocityX) > 500 || Math.abs(velocityY) > 500) && !isZooming) {
                    PointF vTranslateEnd = new PointF(vTranslate.x + (velocityX * 0.25f), vTranslate.y + (velocityY * 0.25f));
                    float sCenterXEnd = ((getWidth()/2) - vTranslateEnd.x)/scale;
                    float sCenterYEnd = ((getHeight()/2) - vTranslateEnd.y)/scale;
                    new AnimationBuilder(new PointF(sCenterXEnd, sCenterYEnd)).withEasing(EASE_OUT_QUAD).withPanLimited(false).start();
                    return true;
                }
                return super.onFling(e1, e2, velocityX, velocityY);
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                performClick();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (zoomEnabled && imageSourceAvailable) {
                    if (quickScaleEnabled) {
                        vCenterStart = new PointF(e.getX(), e.getY());
                        vTranslateStart.set(vTranslate);
                        scaleStart = scale;

                        isQuickScaling = true;
                        isZooming = true;
                        quickScaleCenter = viewToSourceCoord(vCenterStart);
                        quickScaleLastDistance = -1F;
                        quickScaleLastPoint = new PointF(quickScaleCenter.x, quickScaleCenter.y);
                        quickScaleMoved = false;

                        setGestureDetector(context);

                        // We really want to get events in onTouchEvent after this, so don't return true
                        return false;
                    }

                    float doubleTapZoomScale = Math.min(maxScale, ScaleImageViewBase.this.doubleTapZoomScale);
                    boolean zoomIn = scale <= doubleTapZoomScale * 0.9;
                    float targetScale = zoomIn ? doubleTapZoomScale : minScale();
                    PointF targetSCenter = viewToSourceCoord(e.getX(), e.getY());
                    if (doubleTapZoomStyle == ZOOM_FOCUS_CENTER_IMMEDIATE) {
                        setScaleAndCenter(targetScale, targetSCenter);
                    } else if (doubleTapZoomStyle == ZOOM_FOCUS_CENTER || !zoomIn) {
                        new AnimationBuilder(targetScale, targetSCenter).withInterruptible(false).start();
                    } else if (doubleTapZoomStyle == ZOOM_FOCUS_FIXED) {
                        new AnimationBuilder(targetScale, targetSCenter, new PointF(e.getX(), e.getY())).withInterruptible(false).start();
                    }

                    // Hacky solution for #15 - after a double tap the GestureDetector gets in a state where the next
                    // fling is ignored, so here we replace it with a new one.
                    setGestureDetector(context);

                    invalidate();
                    return true;
                }
                return super.onDoubleTapEvent(e);
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setOnLongClickListener(OnLongClickListener onLongClickListener) {
        this.onLongClickListener = onLongClickListener;
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
        satTemp.scale = scale;
        satTemp.vTranslate.set(vTranslate);
        fitToBounds(center, satTemp);
        scale = satTemp.scale;
        vTranslate.set(satTemp.vTranslate);
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
                (int) sourceToViewX(sRect.left),
                (int) sourceToViewY(sRect.top),
                (int) sourceToViewX(sRect.right),
                (int) sourceToViewY(sRect.bottom)
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

    /**
     * Releases all resources the view is using and resets the state, nulling any fields that use significant memory.
     * After you have called this method, the view can be re-used by setting a new image. Settings are remembered
     * but state (scale and center) is forgotten. You can restore these yourself if required.
     */
    public void recycle() {
        reset(true);
        bitmapPaint = null;
        debugPaint = null;
        backgroundPaint = null;
    }

    protected void reset(boolean isNewImage) {
        scale = 0f;
        scaleStart = 0f;
        vTranslate.set(0, 0);
        vTranslateStart.set(0, 0);
        pendingScale = 0f;
        sPendingCenter = null;
        sRequestedCenter = null;
        anim = null;
        isZooming = false;
        isPanning = false;
        isQuickScaling = false;
        maxTouchCount = 0;
        vCenterStart = null;
        vDistStart = 0;
        quickScaleCenter = null;
        quickScaleLastDistance = 0f;
        quickScaleLastPoint = null;
        quickScaleMoved = false;
        if (isNewImage) {
            setSourceSize(0, 0);
            sourceOrientation = 0;
            imageSourceAvailable = false;
            discardImageDataSource();
            imageDataSource = null;
        }
        forceCenterOnNextDraw();
        setGestureDetector(getContext()); // TODO check if this can be avoided
    }

    protected abstract void discardImageDataSource();

    /**
     * Pythagoras distance between two points.
     */
    protected static float distance(float x0, float x1, float y0, float y1) {
        float x = x0 - x1;
        float y = y0 - y1;
        return FloatMath.sqrt(x * x + y * y);
    }

    /**
     * On resize, preserve center and scale. Various behaviours are possible, override this method to use another.
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (imageSourceAvailable) {
            setScaleAndCenter(getScale(), getCenter());
        }
    }

    protected boolean canDraw() {
        return getSourceWidth() > 0 || getSourceHeight() > 0 &&
            imageDataSource != null && getWidth() > 0 && getHeight() > 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        createPaints();
        if (!canDraw()) {
            return;
        }
        if (!isInitialImageDataLoaded()) {
            preloadInitialImageData(canvas);
            return;
        }
        updateAnimation();
        drawImageData(canvas);
        if (debug) {
            drawDebugInformation(canvas);
        }
    }

    /**
     * Sets scale and translate ready for the next draw.
     */
    private void prepareDraw() {
        // If waiting to translate to new center position, set translate now
        if (sPendingCenter != null && pendingScale != null) {
            scale = pendingScale;
            vTranslate.x = (getWidth()/2) - (scale * sPendingCenter.x);
            vTranslate.y = (getHeight()/2) - (scale * sPendingCenter.y);
            sPendingCenter = null;
            pendingScale = null;
            fitToBounds(true);
            refreshImageData(true);
        }

        // On first display of base image set up position, and in other cases make sure scale is correct.
        fitToBounds(false);
    }

    protected void updateAnimation() {
        // If animating scale, calculate current scale and center with easing equations
        if (anim != null) {
            long scaleElapsed = System.currentTimeMillis() - anim.time;
            boolean finished = scaleElapsed > anim.duration;
            scaleElapsed = Math.min(scaleElapsed, anim.duration);
            scale = ease(anim.easing, scaleElapsed, anim.scaleStart, anim.scaleEnd - anim.scaleStart, anim.duration);

            // Apply required animation to the focal point
            float vFocusNowX = ease(anim.easing, scaleElapsed, anim.vFocusStart.x, anim.vFocusEnd.x - anim.vFocusStart.x, anim.duration);
            float vFocusNowY = ease(anim.easing, scaleElapsed, anim.vFocusStart.y, anim.vFocusEnd.y - anim.vFocusStart.y, anim.duration);
            // Find out where the focal point is at this scale and adjust its position to follow the animation path
            vTranslate.x -= sourceToViewX(anim.sCenterEnd.x) - vFocusNowX;
            vTranslate.y -= sourceToViewY(anim.sCenterEnd.y) - vFocusNowY;

            // For translate anims, showing the image non-centered is never allowed, for scaling anims it is during the animation.
            fitToBounds(finished || (anim.scaleStart == anim.scaleEnd));
            refreshImageData(finished);
            if (finished) {
                anim = null;
            }
            invalidate();
        }
    }

    protected abstract void drawImageData(Canvas canvas);

    protected void drawDebugInformation(Canvas canvas) {
        canvas.drawText("Scale: " + String.format("%.2f", scale), 5, 15, debugPaint);
        canvas.drawText("Translate: " + String.format("%.2f", vTranslate.x) + ":" + String.format("%.2f", vTranslate.y), 5, 35, debugPaint);
        PointF center = getCenter();
        canvas.drawText("Source center: " + String.format("%.2f", center.x) + ":" + String.format("%.2f", center.y), 5, 55, debugPaint);

        if (anim != null) {
            PointF vCenterStart = sourceToViewCoord(anim.sCenterStart);
            PointF vCenterEndRequested = sourceToViewCoord(anim.sCenterEndRequested);
            PointF vCenterEnd = sourceToViewCoord(anim.sCenterEnd);
            canvas.drawCircle(vCenterStart.x, vCenterStart.y, 10, debugPaint);
            canvas.drawCircle(vCenterEndRequested.x, vCenterEndRequested.y, 20, debugPaint);
            canvas.drawCircle(vCenterEnd.x, vCenterEnd.y, 25, debugPaint);
            canvas.drawCircle(getWidth()/2, getHeight()/2, 30, debugPaint);
        }
    }

    /**
     * Handle touch events. One finger pans, and two finger pinch and zoom plus panning.
     */
    @Override @SuppressWarnings("deprecation")
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        // During non-interruptible anims, ignore all touch events
        if (anim != null && !anim.interruptible) {
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        } else {
            anim = null;
        }

        // Abort if not ready
        if (vTranslate == null) {
            return true;
        }
        // Detect flings, taps and double taps
        if (!isQuickScaling && (detector == null || detector.onTouchEvent(event))) {
            isZooming = false;
            isPanning = false;
            maxTouchCount = 0;
            return true;
        }

        if (vCenterStart == null) { vCenterStart = new PointF(0, 0); }

        int touchCount = event.getPointerCount();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_1_DOWN:
            case MotionEvent.ACTION_POINTER_2_DOWN:
                anim = null;
                getParent().requestDisallowInterceptTouchEvent(true);
                maxTouchCount = Math.max(maxTouchCount, touchCount);
                if (touchCount >= 2) {
                    if (zoomEnabled) {
                        // Start pinch to zoom. Calculate distance between touch points and center point of the pinch.
                        float distance = distance(event.getX(0), event.getX(1), event.getY(0), event.getY(1));
                        scaleStart = scale;
                        vDistStart = distance;
                        vTranslateStart.set(vTranslate.x, vTranslate.y);
                        vCenterStart.set((event.getX(0) + event.getX(1))/2, (event.getY(0) + event.getY(1))/2);
                    } else {
                        // Abort all gestures on second touch
                        maxTouchCount = 0;
                    }
                    // Cancel long click timer
                    handler.removeMessages(MESSAGE_LONG_CLICK);
                } else if (!isQuickScaling) {
                    // Start one-finger pan
                    vTranslateStart.set(vTranslate.x, vTranslate.y);
                    vCenterStart.set(event.getX(), event.getY());

                    // Start long click timer
                    handler.sendEmptyMessageDelayed(MESSAGE_LONG_CLICK, 600);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                boolean consumed = false;
                if (maxTouchCount > 0) {
                    if (touchCount >= 2) {
                        // Calculate new distance between touch points, to scale and pan relative to start values.
                        float vDistEnd = distance(event.getX(0), event.getX(1), event.getY(0), event.getY(1));
                        float vCenterEndX = (event.getX(0) + event.getX(1))/2;
                        float vCenterEndY = (event.getY(0) + event.getY(1))/2;

                        if (zoomEnabled && (distance(vCenterStart.x, vCenterEndX, vCenterStart.y, vCenterEndY) > 5 || Math.abs(vDistEnd - vDistStart) > 5 || isPanning)) {
                            isZooming = true;
                            isPanning = true;
                            consumed = true;

                            scale = Math.min(maxScale, (vDistEnd / vDistStart) * scaleStart);

                            if (scale <= minScale()) {
                                // Minimum scale reached so don't pan. Adjust start settings so any expand will zoom in.
                                vDistStart = vDistEnd;
                                scaleStart = minScale();
                                vCenterStart.set(vCenterEndX, vCenterEndY);
                                vTranslateStart.set(vTranslate);
                            } else if (panEnabled) {
                                // Translate to place the source image coordinate that was at the center of the pinch at the start
                                // at the center of the pinch now, to give simultaneous pan + zoom.
                                float vLeftStart = vCenterStart.x - vTranslateStart.x;
                                float vTopStart = vCenterStart.y - vTranslateStart.y;
                                float vLeftNow = vLeftStart * (scale/scaleStart);
                                float vTopNow = vTopStart * (scale/scaleStart);
                                vTranslate.x = vCenterEndX - vLeftNow;
                                vTranslate.y = vCenterEndY - vTopNow;
                            } else if (sRequestedCenter != null) {
                                // With a center specified from code, zoom around that point.
                                vTranslate.x = (getWidth()/2) - (scale * sRequestedCenter.x);
                                vTranslate.y = (getHeight()/2) - (scale * sRequestedCenter.y);
                            } else {
                                // With no requested center, scale around the image center.
                                vTranslate.x = (getWidth()/2) - (scale * (rotatedSourceWidth()/2));
                                vTranslate.y = (getHeight()/2) - (scale * (rotatedSourceHeight()/2));
                            }

                            fitToBounds(true);
                            refreshImageData(false);
                        }
                    } else if (isQuickScaling) {
                        // One finger zoom
                        // Stole Google's Magical Formula™ to make sure it feels the exact same
                        float dist = Math.abs(vCenterStart.y - event.getY()) * 2 + quickScaleThreshold;

                        if (quickScaleLastDistance == -1F) quickScaleLastDistance = dist;
                        boolean isUpwards = event.getY() > quickScaleLastPoint.y;
                        quickScaleLastPoint.set(0, event.getY());

                        float spanDiff = (Math.abs(1 - (dist / quickScaleLastDistance)) * 0.5F);

                        if (spanDiff > 0.03f || quickScaleMoved) {
                            quickScaleMoved = true;

                            float multiplier = 1;
                            if (quickScaleLastDistance > 0) {
                                multiplier = isUpwards ? (1 + spanDiff) : (1 - spanDiff);
                            }

                            scale = Math.max(minScale(), Math.min(maxScale, scale * multiplier));

                            if (panEnabled) {
                                float vLeftStart = vCenterStart.x - vTranslateStart.x;
                                float vTopStart = vCenterStart.y - vTranslateStart.y;
                                float vLeftNow = vLeftStart * (scale/scaleStart);
                                float vTopNow = vTopStart * (scale/scaleStart);
                                vTranslate.x = vCenterStart.x - vLeftNow;
                                vTranslate.y = vCenterStart.y - vTopNow;
                            } else if (sRequestedCenter != null) {
                                // With a center specified from code, zoom around that point.
                                vTranslate.x = (getWidth()/2) - (scale * sRequestedCenter.x);
                                vTranslate.y = (getHeight()/2) - (scale * sRequestedCenter.y);
                            } else {
                                // With no requested center, scale around the image center.
                                vTranslate.x = (getWidth()/2) - (scale * (rotatedSourceWidth()/2));
                                vTranslate.y = (getHeight()/2) - (scale * (rotatedSourceHeight()/2));
                            }
                        }

                        quickScaleLastDistance = dist;

                        fitToBounds(true);
                        refreshImageData(false);

                        consumed = true;
                    } else if (!isZooming) {
                        // One finger pan - translate the image. We do this calculation even with pan disabled so click
                        // and long click behaviour is preserved.
                        float dx = Math.abs(event.getX() - vCenterStart.x);
                        float dy = Math.abs(event.getY() - vCenterStart.y);
                        if (dx > 5 || dy > 5 || isPanning) {
                            consumed = true;
                            vTranslate.x = vTranslateStart.x + (event.getX() - vCenterStart.x);
                            vTranslate.y = vTranslateStart.y + (event.getY() - vCenterStart.y);

                            float lastX = vTranslate.x;
                            float lastY = vTranslate.y;
                            fitToBounds(true);
                            boolean atXEdge = lastX != vTranslate.x;
                            boolean edgeXSwipe = atXEdge && dx > dy && !isPanning;
                            boolean yPan = lastY == vTranslate.y && dy > 15;
                            if (!edgeXSwipe && (!atXEdge || yPan || isPanning)) {
                                isPanning = true;
                            } else if (dx > 5) {
                                // Haven't panned the image, and we're at the left or right edge. Switch to page swipe.
                                maxTouchCount = 0;
                                handler.removeMessages(MESSAGE_LONG_CLICK);
                                getParent().requestDisallowInterceptTouchEvent(false);
                            }

                            if (!panEnabled) {
                                vTranslate.x = vTranslateStart.x;
                                vTranslate.y = vTranslateStart.y;
                                getParent().requestDisallowInterceptTouchEvent(false);
                            }
                            refreshImageData(false);
                        }
                    }
                }
                if (consumed) {
                    handler.removeMessages(MESSAGE_LONG_CLICK);
                    invalidate();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_POINTER_2_UP:
                handler.removeMessages(MESSAGE_LONG_CLICK);
                if (isQuickScaling) {
                    isQuickScaling = false;
                    if (!quickScaleMoved) {
                        float doubleTapZoomScale = Math.min(maxScale, ScaleImageViewBase.this.doubleTapZoomScale);
                        boolean zoomIn = scale <= doubleTapZoomScale * 0.9;
                        float targetScale = zoomIn ? doubleTapZoomScale : minScale();
                        if (doubleTapZoomStyle == ZOOM_FOCUS_CENTER_IMMEDIATE) {
                            setScaleAndCenter(targetScale, quickScaleCenter);
                        } else if (doubleTapZoomStyle == ZOOM_FOCUS_CENTER || !zoomIn) {
                            new AnimationBuilder(targetScale, quickScaleCenter).withInterruptible(false).start();
                        } else if (doubleTapZoomStyle == ZOOM_FOCUS_FIXED) {
                            new AnimationBuilder(targetScale, quickScaleCenter, vCenterStart).withInterruptible(false).start();
                        }
                        invalidate();
                    }
                }
                if (maxTouchCount > 0 && (isZooming || isPanning)) {
                    if (isZooming && touchCount == 2) {
                        // Convert from zoom to pan with remaining touch
                        isPanning = true;
                        vTranslateStart.set(vTranslate.x, vTranslate.y);
                        if (event.getActionIndex() == 1) {
                            vCenterStart.set(event.getX(0), event.getY(0));
                        } else {
                            vCenterStart.set(event.getX(1), event.getY(1));
                        }
                    }
                    if (touchCount < 3) {
                        // End zooming when only one touch point
                        isZooming = false;
                    }
                    if (touchCount < 2) {
                        // End panning when no touch points
                        isPanning = false;
                        maxTouchCount = 0;
                    }
                    // load of more image data
                    refreshImageData(true);
                    return true;
                }
                if (touchCount == 1) {
                    isZooming = false;
                    isPanning = false;
                    maxTouchCount = 0;
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    protected boolean isImageSourceAvailable() {
        return imageSourceAvailable;
    }

    /**
     * Subclasses can override this method to be informed when the view is set up and ready for rendering, so they can
     * skip their own rendering until the base layer (and its scale and translate) are known.
     */
    protected void onImageReady() {
    }

    /**
     * After the image has been changed or rotated the next call to draw, with dimensions known, should recenter the image.
     * Setting pending scale and center forces this.
     */
    // TODO check if this method is needed
    private void forceCenterOnNextDraw() {
        this.sPendingCenter = new PointF(0, 0);
        this.scale = 0f;
    }

    public void onImageSourceAvailable(ImageDataSource imageDataSource, int imageWidth, int imageHeight, int orientation) {
        this.imageDataSource = imageDataSource;
        this.sourceOrientation = orientation;
        setSourceSize(imageWidth, imageHeight);
        vTranslate.set(vTranslateForSCenter(rotatedSourceWidth() / 2, rotatedSourceHeight() / 2, scale));
        forceCenterOnNextDraw();
        requestLayout();
        invalidate();

        // Inform subclasses that the image is ready and scale and translate are set.
        if (!imageSourceAvailable) {
            prepareDraw();
            imageSourceAvailable = true;
            onImageReady();
            if (imageSizeListener != null) {
                imageSizeListener.onImageSizeAvailable(imageWidth, imageHeight);
            }
        }
    }

    /**
     * Creates a panning animation builder, that when started will animate the image to place the given coordinates of
     * the image in the center of the screen. If doing this would move the image beyond the edges of the screen, the
     * image is instead animated to move the center point as near to the center of the screen as is allowed - it's
     * guaranteed to be on screen.
     * @param sCenter Target center point
     * @return {@link AnimationBuilder} instance. Call {@link SubsamplingScaleImageView.AnimationBuilder#start()} to start the anim.
     */
    public AnimationBuilder animateCenter(PointF sCenter) {
        if (!isImageReady()) {
            return null;
        }
        return new AnimationBuilder(sCenter);
    }

    /**
     * Creates a scale animation builder, that when started will animate a zoom in or out. If this would move the image
     * beyond the panning limits, the image is automatically panned during the animation.
     * @param scale Target scale.
     * @return {@link AnimationBuilder} instance. Call {@link SubsamplingScaleImageView.AnimationBuilder#start()} to start the anim.
     */
    public AnimationBuilder animateScale(float scale) {
        if (!isImageReady()) {
            return null;
        }
        return new AnimationBuilder(scale);
    }

    /**
     * Creates a scale animation builder, that when started will animate a zoom in or out. If this would move the image
     * beyond the panning limits, the image is automatically panned during the animation.
     * @param scale Target scale.
     * @return {@link AnimationBuilder} instance. Call {@link SubsamplingScaleImageView.AnimationBuilder#start()} to start the anim.
     */
    public AnimationBuilder animateScaleAndCenter(float scale, PointF sCenter) {
        if (!isImageReady()) {
            return null;
        }
        return new AnimationBuilder(scale, sCenter);
    }

    /**
     * Call to find whether the view is initialised and ready for rendering.
     */
    public boolean isImageReady() {
        return isImageSourceAvailable() && vTranslate != null && sourceWidth > 0 && sourceHeight > 0 && isInitialImageDataLoaded();
    }

    // TODO find better name to reflect that the resolution and/od viewport changed and that data might be unloaded
    protected abstract void refreshImageData(boolean loadIfNecessary);

    protected abstract boolean isInitialImageDataLoaded();

    protected abstract void preloadInitialImageData(Canvas canvas);

    /**
     * Apply a selected type of easing.
     * @param type Easing type, from static fields
     * @param time Elapsed time
     * @param from Start value
     * @param change Target value
     * @param duration Anm duration
     * @return Current value
     */
    private static float ease(int type, long time, float from, float change, long duration) {
        switch (type) {
            case EASE_IN_OUT_QUAD:
                return easeInOutQuad(time, from, change, duration);
            case EASE_OUT_QUAD:
                return easeOutQuad(time, from, change, duration);
            default:
                throw new IllegalStateException("Unexpected easing type: " + type);
        }
    }

    /**
     * Quadratic easing for fling. With thanks to Robert Penner - http://gizma.com/easing/
     * @param time Elapsed time
     * @param from Start value
     * @param change Target value
     * @param duration Anm duration
     * @return Current value
     */
    private static float easeOutQuad(long time, float from, float change, long duration) {
        float progress = (float)time/(float)duration;
        return -change * progress*(progress-2) + from;
    }

    /**
     * Quadratic easing for scale and center animations. With thanks to Robert Penner - http://gizma.com/easing/
     * @param time Elapsed time
     * @param from Start value
     * @param change Target value
     * @param duration Anm duration
     * @return Current value
     */
    private static float easeInOutQuad(long time, float from, float change, long duration) {
        float timeF = time/(duration/2f);
        if (timeF < 1) {
            return (change/2f * timeF * timeF) + from;
        } else {
            timeF--;
            return (-change/2f) * (timeF * (timeF - 2) - 1) + from;
        }
    }

    public class AnimationBuilder {

        private final float targetScale;
        private final PointF targetSCenter;
        private final PointF vFocus;
        private long duration = 500;
        private int easing = EASE_IN_OUT_QUAD;
        private boolean interruptible = true;
        private boolean panLimited = true;

        protected AnimationBuilder(PointF sCenter) {
            this.targetScale = scale;
            this.targetSCenter = sCenter;
            this.vFocus = null;
        }

        protected AnimationBuilder(float scale) {
            this.targetScale = scale;
            this.targetSCenter = getCenter();
            this.vFocus = null;
        }

        protected AnimationBuilder(float scale, PointF sCenter) {
            this.targetScale = scale;
            this.targetSCenter = sCenter;
            this.vFocus = null;
        }

        protected AnimationBuilder(float scale, PointF sCenter, PointF vFocus) {
            this.targetScale = scale;
            this.targetSCenter = sCenter;
            this.vFocus = vFocus;
        }

        /**
         * Desired duration of the anim in milliseconds. Default is 500.
         * @param duration duration in milliseconds.
         * @return this builder for method chaining.
         */
        public AnimationBuilder withDuration(long duration) {
            this.duration = duration;
            return this;
        }

        /**
         * Whether the animation can be interrupted with a touch. Default is true.
         * @param interruptible interruptible flag.
         * @return this builder for method chaining.
         */
        public AnimationBuilder withInterruptible(boolean interruptible) {
            this.interruptible = interruptible;
            return this;
        }

        /**
         * Set the easing style. See static fields. {@link #EASE_IN_OUT_QUAD} is recommended, and the default.
         * @param easing easing style.
         * @return this builder for method chaining.
         */
        public AnimationBuilder withEasing(int easing) {
            if (!VALID_EASING_STYLES.contains(easing)) {
                throw new IllegalArgumentException("Unknown easing type: " + easing);
            }
            this.easing = easing;
            return this;
        }

        /**
         * Only for internal use. When set to true, the animation proceeds towards the actual end point - the nearest
         * point to the center allowed by pan limits. When false, animation is in the direction of the requested end
         * point and is stopped when the limit for each axis is reached. The latter behaviour is used for flings but
         * nothing else.
         */
        private AnimationBuilder withPanLimited(boolean panLimited) {
            this.panLimited = panLimited;
            return this;
        }

        /**
         * Starts the animation.
         */
        public void start() {
            int vxCenter = getPaddingLeft() + (getWidth() - getPaddingRight() - getPaddingLeft())/2;
            int vyCenter = getPaddingTop() + (getHeight() - getPaddingBottom() - getPaddingTop())/2;
            float targetScale = limitedScale(this.targetScale);
            PointF targetSCenter = panLimited ? limitedSCenter(this.targetSCenter.x, this.targetSCenter.y, targetScale, new PointF()) : this.targetSCenter;
            anim = new Anim();
            anim.scaleStart = scale;
            anim.scaleEnd = targetScale;
            anim.time = System.currentTimeMillis();
            anim.sCenterEndRequested = targetSCenter;
            anim.sCenterStart = getCenter();
            anim.sCenterEnd = targetSCenter;
            anim.vFocusStart = sourceToViewCoord(targetSCenter);
            anim.vFocusEnd = new PointF(
                    vxCenter,
                    vyCenter
            );
            anim.duration = duration;
            anim.interruptible = interruptible;
            anim.easing = easing;
            anim.time = System.currentTimeMillis();

            if (vFocus != null) {
                // Calculate where translation will be at the end of the anim
                float vTranslateXEnd = vFocus.x - (targetScale * anim.sCenterStart.x);
                float vTranslateYEnd = vFocus.y - (targetScale * anim.sCenterStart.y);
                ScaleAndTranslate satEnd = new ScaleAndTranslate(targetScale, vTranslateXEnd, vTranslateYEnd);
                // Fit the end translation into bounds
                fitToBounds(true, satEnd);
                // Adjust the position of the focus point at end so image will be in bounds
                anim.vFocusEnd = new PointF(
                        vFocus.x + (satEnd.vTranslate.x - vTranslateXEnd),
                        vFocus.y + (satEnd.vTranslate.y - vTranslateYEnd)
                );
            }

            invalidate();
        }
    }

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
        public float scale;
        public final PointF vTranslate = new PointF();

        public ScaleAndTranslate(float scale, float translationX, float translationY) {
            this.scale = scale;
            vTranslate.set(translationX, translationY);
        }
    }
}
