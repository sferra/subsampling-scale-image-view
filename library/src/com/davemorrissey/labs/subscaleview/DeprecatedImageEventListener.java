package com.davemorrissey.labs.subscaleview;

/**
 * @deprecated Do not use this class in new code. Use {@link com.davemorrissey.labs.subscaleview.ImageSizeDecoderListener} instead
 */
public interface DeprecatedImageEventListener {

    /**
     * Called when the dimensions of the image are known. The image is not visible at this point.
     */
    void onImageReady();

    /**
     * Called when the image file could not be loaded. This method cannot be relied upon; certain
     * encoding types of supported image formats can result in corrupt or blank images being loaded
     * and displayed with no detectable error.
     * @param e The exception thrown. This error is also logged by the view.
     */
    void onInitialisationError(Exception e);
}
