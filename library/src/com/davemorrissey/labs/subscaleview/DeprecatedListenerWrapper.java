package com.davemorrissey.labs.subscaleview;

class DeprecatedListenerWrapper implements ImageSizeDecoderListener {

    public final DeprecatedImageEventListener listener;

    DeprecatedListenerWrapper(DeprecatedImageEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void onImageSizeAvailable(int width, int height) {
        listener.onImageReady();
    }

    @Override
    public void onImageSizeDecodingFailed(Exception e) {
        listener.onInitialisationError(e);
    }
}
