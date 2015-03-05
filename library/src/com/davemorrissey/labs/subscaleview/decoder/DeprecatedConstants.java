package com.davemorrissey.labs.subscaleview.decoder;

import com.davemorrissey.labs.subscaleview.Orientation;

/**
 * Deprecated constants which may be removed in a future versions are kept here to prevent API breakage.
 */
public interface DeprecatedConstants {
    /**
     * Attempt to use EXIF information on the image to rotate it. Works for external files only.
     * @deprecated Use {@link com.davemorrissey.labs.subscaleview.Orientation#EXIF} instead
     */
    @Deprecated
    public static final int ORIENTATION_USE_EXIF = Orientation.EXIF.rotationDegrees;
    /**
     * Display the image file in its native orientation.
     * @deprecated Use {@link com.davemorrissey.labs.subscaleview.Orientation#DEGREES_0} instead
     */
    @Deprecated
    public static final int ORIENTATION_0 = Orientation.DEGREES_0.rotationDegrees;
    /**
     * Rotate the image 90 degrees clockwise.
     * @deprecated Use {@link com.davemorrissey.labs.subscaleview.Orientation#DEGREES_90} instead
     */
    @Deprecated
    public static final int ORIENTATION_90 = Orientation.DEGREES_90.rotationDegrees;
    /**
     * Rotate the image 180 degrees.
     * @deprecated Use {@link com.davemorrissey.labs.subscaleview.Orientation#DEGREES_180} instead
     */
    @Deprecated
    public static final int ORIENTATION_180 = Orientation.DEGREES_180.rotationDegrees;
    /**
     * Rotate the image 270 degrees clockwise.
     * @deprecated Use {@link com.davemorrissey.labs.subscaleview.Orientation#DEGREES_270} instead
     */
    @Deprecated
    public static final int ORIENTATION_270 = Orientation.DEGREES_270.rotationDegrees;

}
