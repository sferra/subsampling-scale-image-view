package com.davemorrissey.labs.subscaleview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import com.davemorrissey.labs.subscaleview.decoder.DeprecatedConstants;

public abstract  class ScaleImageViewBase extends View implements DeprecatedConstants {

    protected static final String FILE_SCHEME = "file:///";
    protected static final String ASSET_SCHEME = "file:///android_asset/";

    // Source image dimensions and orientation - dimensions relate to the unrotated image
    protected int sWidth;
    protected int sHeight;
    protected int sOrientation;

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
            return sOrientation;
        } else {
            return orientation.rotationDegrees;
        }
    }

    protected abstract void reset(boolean isNewImage);
}
