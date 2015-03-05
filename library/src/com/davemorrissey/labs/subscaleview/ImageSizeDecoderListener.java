package com.davemorrissey.labs.subscaleview;

public interface ImageSizeDecoderListener {
    /**
     * Called when the dimensions of the image are known.
     */
    void onImageSizeAvailable(int width, int height);

    /**
     * Called when the image size could not be determined. This method cannot be relied upon; certain
     * encoding types of supported image formats can result in corrupt or blank images being loaded
     * and displayed with no detectable error.
     * @param e The exception thrown. This error is also logged by the view.
     */
    void onImageSizeDecodingFailed(Exception e);
}
