package com.davemorrissey.labs.subscaleview;

public interface TileImageLoaderListener {

    /**
     * Called when the lowest resolution base layer of tiles are loaded and about to be rendered,
     * in other words the view will no longer be blank. You can use this event as a trigger to
     * display overlays, remove loading animations etc.
     */
    void onBaseLayerReady();

    /**
     * Called when an image tile could not be loaded. This method cannot be relied upon; certain
     * encoding types of supported image formats can result in corrupt or blank images being loaded
     * and displayed with no detectable error.
     * @param e The exception thrown. This error is logged by the view.
     */
    void onTileLoadError(Exception e);
}
