package com.davemorrissey.labs.subscaleview;

public enum Orientation {
    /** Attempt to use EXIF information on the image to rotate it. Works for external files only. */
    EXIF(-1),
    /** Display the image file in its native orientation. */
    DEGREES_0(0),
    /** Rotate the image 90 degrees clockwise. */
    DEGREES_90(90),
    /** Rotate the image 180 degrees. */
    DEGREES_180(180),
    /** Rotate the image 270 degrees clockwise. */
    DEGREES_270(270),
    ;

    public final int rotationDegrees;

    Orientation(int rotationDegrees) {
        this.rotationDegrees = rotationDegrees;
    }

    /**
     * Helper method used for mapping the rotation values to enum values.
     * @param degrees An integer clockwise rotation value.
     * @return The corresponding enum value of null if none was found.
     */
    public static Orientation fromRotationDegrees(int degrees) {
        for (Orientation value : Orientation.values()) {
            if (value.rotationDegrees == degrees) {
                return value;
            }
        }
        return null;
    }
}
