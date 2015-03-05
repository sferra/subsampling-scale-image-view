package com.davemorrissey.labs.subscaleview;

import com.davemorrissey.labs.subscaleview.Orientation;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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


    // TODO deprecate all below
    /** During zoom animation, keep the point of the image that was tapped in the same place, and scale the image around it. */
    public static final int ZOOM_FOCUS_FIXED = 1;
    /** During zoom animation, move the point of the image that was tapped to the center of the screen. */
    public static final int ZOOM_FOCUS_CENTER = 2;
    /** Zoom in to and center the tapped point immediately without animating. */
    public static final int ZOOM_FOCUS_CENTER_IMMEDIATE = 3;

    public static final List<Integer> VALID_ZOOM_STYLES = Collections.unmodifiableList(Arrays.asList(ZOOM_FOCUS_FIXED, ZOOM_FOCUS_CENTER, ZOOM_FOCUS_CENTER_IMMEDIATE));

    /** Quadratic ease out. Not recommended for scale animation, but good for panning. */
    public static final int EASE_OUT_QUAD = 1;
    /** Quadratic ease in and out. */
    public static final int EASE_IN_OUT_QUAD = 2;

    public static final List<Integer> VALID_EASING_STYLES = Collections.unmodifiableList(Arrays.asList(EASE_IN_OUT_QUAD, EASE_OUT_QUAD));

    /** Don't allow the image to be panned off screen. As much of the image as possible is always displayed, centered in the view when it is smaller. This is the best option for galleries. */
    public static final int PAN_LIMIT_INSIDE = 1;
    /** Allows the image to be panned until it is just off screen, but no further. The edge of the image will stop when it is flush with the screen edge. */
    public static final int PAN_LIMIT_OUTSIDE = 2;
    /** Allows the image to be panned until a corner reaches the center of the screen but no further. Useful when you want to pan any spot on the image to the exact center of the screen. */
    public static final int PAN_LIMIT_CENTER = 3;

    public static final List<Integer> VALID_PAN_LIMITS = Collections.unmodifiableList(Arrays.asList(PAN_LIMIT_INSIDE, PAN_LIMIT_OUTSIDE, PAN_LIMIT_CENTER));

    /** Scale the image so that both dimensions of the image will be equal to or less than the corresponding dimension of the view. The image is then centered in the view. This is the default behaviour and best for galleries. */
    public static final int SCALE_TYPE_CENTER_INSIDE = 1;
    /** Scale the image uniformly so that both dimensions of the image will be equal to or larger than the corresponding dimension of the view. The image is then centered in the view. */
    public static final int SCALE_TYPE_CENTER_CROP = 2;
    /** Scale the image so that both dimensions of the image will be equal to or less than the maxScale and equal to or larger than minScale. The image is then centered in the view. */
    public static final int SCALE_TYPE_CUSTOM = 3;

    public static final List<Integer> VALID_SCALE_TYPES = Collections.unmodifiableList(Arrays.asList(SCALE_TYPE_CENTER_CROP, SCALE_TYPE_CENTER_INSIDE, SCALE_TYPE_CUSTOM));

}
