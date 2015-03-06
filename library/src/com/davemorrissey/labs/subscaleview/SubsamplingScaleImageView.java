/*
Copyright 2013-2015 David Morrissey

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.davemorrissey.labs.subscaleview;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build.VERSION;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;

import com.davemorrissey.labs.subscaleview.R.styleable;
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder;
import com.davemorrissey.labs.subscaleview.decoder.SkiaImageRegionDecoder;
import com.davemorrissey.labs.subscaleview.task.ImageRegionDecoderTask;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Displays an image subsampled as necessary to avoid loading too much image data into memory. After a pinch to zoom in,
 * a set of image tiles subsampled at higher resolution are loaded and displayed over the base layer. During pinch and
 * zoom, tiles off screen or higher/lower resolution than required are discarded from memory.
 *
 * Tiles are no larger than the max supported bitmap size, so with large images tiling may be used even when zoomed out.
 *
 * v prefixes - coordinates, translations and distances measured in screen (view) pixels
 * s prefixes - coordinates, translations and distances measured in source image pixels (scaled)
 */
public class SubsamplingScaleImageView extends ScaleImageViewBase<ImageRegionDecoder> {

    private static final String TAG = SubsamplingScaleImageView.class.getSimpleName();

    // Density to reach before loading higher resolution tiles
    private int minimumTileDpi = -1;

    // Tile decoder
    private Class<? extends ImageRegionDecoder> decoderClass = SkiaImageRegionDecoder.class;
    private final Object decoderLock = new Object();

    // Sample size used to display the whole image when fully zoomed out
    private int fullImageSampleSize;

    // Map of zoom level to tile grid
    private Map<Integer, List<Tile>> tileMap;

    // Whether a base layer loaded notification has been sent to subclasses
    private boolean baseLayerReadySent = false;
    private TileImageLoaderListener tileLoaderListener;

    public SubsamplingScaleImageView(Context context, AttributeSet attr) {
        super(context, attr);
        // Handle XML attributes
        if (attr != null) {
            TypedArray typedAttr = getContext().obtainStyledAttributes(attr, styleable.SubsamplingScaleImageView);
            if (typedAttr.hasValue(styleable.SubsamplingScaleImageView_assetName)) {
                String assetName = typedAttr.getString(styleable.SubsamplingScaleImageView_assetName);
                if (assetName != null && assetName.length() > 0) {
                    setImageAsset(assetName);
                }
            }
            if (typedAttr.hasValue(styleable.SubsamplingScaleImageView_src)) {
                int resId = typedAttr.getResourceId(styleable.SubsamplingScaleImageView_src, 0);
                if (resId > 0) {
                    setImageResource(resId);
                }
            }
            if (typedAttr.hasValue(styleable.SubsamplingScaleImageView_panEnabled)) {
                setPanEnabled(typedAttr.getBoolean(styleable.SubsamplingScaleImageView_panEnabled, true));
            }
            if (typedAttr.hasValue(styleable.SubsamplingScaleImageView_zoomEnabled)) {
                setZoomEnabled(typedAttr.getBoolean(styleable.SubsamplingScaleImageView_zoomEnabled, true));
            }
            if (typedAttr.hasValue(styleable.SubsamplingScaleImageView_tileBackgroundColor)) {
                setTileBackgroundColor(typedAttr.getColor(styleable.SubsamplingScaleImageView_tileBackgroundColor, Color.argb(0, 0, 0, 0)));
            }
            typedAttr.recycle();
        }
    }

    public SubsamplingScaleImageView(Context context) {
        this(context, null);
    }

    /**
     * Sets the image orientation. It's best to call this before setting the image file or asset, because it may waste
     * loading of tiles. However, this can be freely called at any time.
     */
    public void setOrientation(final Orientation orientation) {
        super.setOrientation(orientation);
    }

    /**
     * Sets the image orientation. It's best to call this before setting the image file or asset, because it may waste
     * loading of tiles. However, this can be freely called at any time.
     * @deprecated Use {@link #setOrientation(Orientation)} instead.
     */
    @SuppressWarnings("deprecation")
    public final void setOrientation(int orientation) {
        super.setOrientation(orientation);
    }

    /**
     * Display an image from a file in internal or external storage.
     * @param fileUri URI of the file to display e.g. '/sdcard/DCIM1000.PNG' or 'file:///scard/DCIM1000.PNG' (these are equivalent).
     * @deprecated Method name is outdated, other URIs are now accepted so use {@link #setImageUri(android.net.Uri)} or {@link #setImageUri(String)}.
     */
    @Deprecated
    public final void setImageFile(String fileUri) {
        setImageUri(fileUri);
    }

    /**
     * Display an image from a file in internal or external storage, starting with a given orientation setting, scale
     * and center. This is the best method to use when you want scale and center to be restored after screen orientation
     * change; it avoids any redundant loading of tiles in the wrong orientation.
     * @param fileUri URI of the file to display e.g. '/sdcard/DCIM1000.PNG' or 'file:///scard/DCIM1000.PNG' (these are equivalent).
     * @param state State to be restored. Nullable.
     * @deprecated Method name is outdated, other URIs are now accepted so use {@link #setImageUri(android.net.Uri, ImageViewState)} or {@link #setImageUri(String, ImageViewState)}.
     */
    @Deprecated
    public final void setImageFile(String fileUri, ImageViewState state) {
        setImageUri(fileUri, state);
    }

    /**
     * Display an image from resources.
     * @param resId Resource ID.
     */
    public final void setImageResource(int resId) {
        setImageResource(resId, null);
    }

    /**
     * Display an image from resources, starting with a given orientation setting, scale and center.
     * This is the best method to use when you want scale and center to be restored after screen orientation
     * change; it avoids any redundant loading of tiles in the wrong orientation.
     * @param resId Resource ID.
     * @param state State to be restored. Nullable.
     */
    public final void setImageResource(int resId, ImageViewState state) {
        setImageUri(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + getContext().getPackageName() + "/" + resId, state);
    }

    /**
     * Display an image from a file in assets.
     * @param assetName asset name.
     */
    public final void setImageAsset(String assetName) {
        setImageAsset(assetName, null);
    }

    /**
     * Display an image from a file in assets, starting with a given orientation setting, scale andcenter. This is the
     * best method to use when you want scale and center to be restored after screen orientation change; it avoids any
     * redundant loading of tiles in the wrong orientation.
     * @param assetName asset name.
     * @param state State to be restored. Nullable.
     */
    public final void setImageAsset(String assetName, ImageViewState state) {
        setImageUri(ASSET_SCHEME + assetName, state);
    }

    /**
     * Display an image from a URI. The URI can be in one of the following formats:
     * File: file:///scard/picture.jpg or /sdcard/picture.jpg
     * Asset: file:///android_asset/picture.png
     * Resource: android.resource://com.example.app/drawable/picture
     * @param uri image URI.
     */
    public final void setImageUri(String uri) {
        setImageUri(uri, null);
    }

    /**
     * Display an image from a URI, starting with a given orientation setting, scale andcenter. This
     * is the best method to use when you want scale and center to be restored after screen orientation
     * change; it avoids any redundant loading of tiles in the wrong orientation. The URI can be in
     * one of the following formats:
     * File: file:///scard/picture.jpg or /sdcard/picture.jpg
     * Asset: file:///android_asset/picture.png
     * Resource: android.resource://com.example.app/drawable/picture
     * @param uri image URI.
     * @param state State to be restored. Nullable.
     */
    public final void setImageUri(String uri, ImageViewState state) {
        if (!uri.contains("://")) {
            if (uri.startsWith("/")) {
                uri = uri.substring(1);
            }
            uri = FILE_SCHEME + uri;
        }
        setImageUri(Uri.parse(uri), state);
    }

    /**
     * Display an image from a URI. The URI can be in one of the following formats:
     * File: file:///scard/picture.jpg or /sdcard/picture.jpg
     * Asset: file:///android_asset/picture.png
     * Resource: android.resource://com.example.app/drawable/picture
     * @param uri image URI.
     */
    public final void setImageUri(Uri uri) {
        setImageUri(uri, null);
    }

    /**
     * Display an image from a URI, starting with a given orientation setting, scale andcenter. This
     * is the best method to use when you want scale and center to be restored after screen orientation
     * change; it avoids any redundant loading of tiles in the wrong orientation. The URI can be in
     * one of the following formats:
     * File: file:///scard/picture.jpg
     * Asset: file:///android_asset/picture.png
     * Resource: android.resource://com.example.app/drawable/picture
     * @param uri image URI.
     * @param state State to be restored. Nullable.
     */
    public final void setImageUri(Uri uri, ImageViewState state) {
        reset(true);
        if (state != null) { restoreState(state); }
        ImageRegionDecoderTask task = new ImageRegionDecoderTask(this, getContext(), decoderClass, uri);
        task.execute();
        invalidate();
    }

    /**
     * Reset all state before setting/changing image or setting new rotation.
     */
    protected void reset(boolean newImage) {
        super.reset(newImage);
        fullImageSampleSize = 0;
        if (newImage) {
            baseLayerReadySent = false;
        }
        if (tileMap != null) {
            for (Map.Entry<Integer, List<Tile>> tileMapEntry : tileMap.entrySet()) {
                for (Tile tile : tileMapEntry.getValue()) {
                    tile.visible = false;
                    if (tile.bitmap != null) {
                        tile.bitmap.recycle();
                        tile.bitmap = null;
                    }
                }
            }
            tileMap = null;
        }
    }

    @Override
    protected void discardImageDataSource() {
        if (imageDataSource != null) {
            synchronized (decoderLock) {
                imageDataSource.recycle();
                imageDataSource = null;
            }
        }
    }

    @Override
    protected boolean isInitialImageDataLoaded() {
        return tileMap != null;
    }

    @Override
    protected void preloadInitialImageData(Canvas canvas) {
        initialiseBaseLayer(getMaxBitmapDimensions(canvas));
    }

    @Override
    protected void drawImageData(Canvas canvas) {
        // Optimum sample size for current scale
        int sampleSize = Math.min(fullImageSampleSize, calculateInSampleSize());

        // First check for missing tiles - if there are any we need the base layer underneath to avoid gaps
        boolean hasMissingTiles = false;
        for (Map.Entry<Integer, List<Tile>> tileMapEntry : tileMap.entrySet()) {
            if (tileMapEntry.getKey() == sampleSize) {
                for (Tile tile : tileMapEntry.getValue()) {
                    if (tile.visible && (tile.loading || tile.bitmap == null)) {
                        hasMissingTiles = true;
                    }
                }
            }
        }

        // Render all loaded tiles. LinkedHashMap used for bottom up rendering - lower res tiles underneath.
        final boolean debugEnabled = isDebugEnabled();
        final Paint debugPaint = getDebugPaint();
        final Paint backgroundPaint = getBackgroundPaint();
        final Paint bitmapPaint = getBitmapPaint();
        for (Map.Entry<Integer, List<Tile>> tileMapEntry : tileMap.entrySet()) {
            if (tileMapEntry.getKey() == sampleSize || hasMissingTiles) {
                for (Tile tile : tileMapEntry.getValue()) {
                    sourceToViewRect(tile.sRect, tile.vRect);
                    if (!tile.loading && tile.bitmap != null) {
                        if (backgroundPaint != null) {
                            canvas.drawRect(tile.vRect, backgroundPaint);
                        }
                        canvas.drawBitmap(tile.bitmap, null, tile.vRect, bitmapPaint);
                        if (debugEnabled) {
                            canvas.drawRect(tile.vRect, debugPaint);
                        }
                    } else if (tile.loading && debugEnabled) {
                        canvas.drawText("LOADING", tile.vRect.left + 5, tile.vRect.top + 35, debugPaint);
                    }
                    if (tile.visible && debugEnabled) {
                        canvas.drawText("ISS " + tile.sampleSize + " RECT " + tile.sRect.top + "," + tile.sRect.left + "," + tile.sRect.bottom + "," + tile.sRect.right, tile.vRect.left + 5, tile.vRect.top + 15, debugPaint);
                    }
                }
            }
        }
    }

    /**
     * Called on first draw when the view has dimensions. Calculates the initial sample size and starts async loading of
     * the base layer image - the whole source subsampled as necessary.
     */
    private synchronized void initialiseBaseLayer(Point maxTileDimensions) {
        fitToBounds(true);

        // Load double resolution - next level will be split into four tiles and at the center all four are required,
        // so don't bother with tiling until the next level 16 tiles are needed.
        fullImageSampleSize = calculateInSampleSize();
        if (fullImageSampleSize > 1) {
            fullImageSampleSize /= 2;
        }

        initialiseTileMap(maxTileDimensions);

        List<Tile> baseGrid = tileMap.get(fullImageSampleSize);
        for (Tile baseTile : baseGrid) {
            BitmapTileTask task = new BitmapTileTask(this, imageDataSource, baseTile);
            task.execute();
        }

    }

    /**
     * Loads the optimum tiles for display at the current scale and translate, so the screen can be filled with tiles
     * that are at least as high resolution as the screen. Frees up bitmaps that are now off the screen.
     * @param load Whether to load the new tiles needed. Use false while scrolling/panning for performance.
     */
    protected void refreshImageData(boolean load) {
        if (!isInitialImageDataLoaded()) {
            return;
        }
        int sampleSize = Math.min(fullImageSampleSize, calculateInSampleSize());

        // Load tiles of the correct sample size that are on screen. Discard tiles off screen, and those that are higher
        // resolution than required, or lower res than required but not the base layer, so the base layer is always present.
        for (Map.Entry<Integer, List<Tile>> tileMapEntry : tileMap.entrySet()) {
            for (Tile tile : tileMapEntry.getValue()) {
                if (tile.sampleSize < sampleSize || (tile.sampleSize > sampleSize && tile.sampleSize != fullImageSampleSize)) {
                    tile.visible = false;
                    if (tile.bitmap != null) {
                        tile.bitmap.recycle();
                        tile.bitmap = null;
                    }
                }
                if (tile.sampleSize == sampleSize) {
                    if (tileVisible(tile)) {
                        tile.visible = true;
                        if (!tile.loading && tile.bitmap == null && load) {
                            BitmapTileTask task = new BitmapTileTask(this, imageDataSource, tile);
                            task.execute();
                        }
                    } else if (tile.sampleSize != fullImageSampleSize) {
                        tile.visible = false;
                        if (tile.bitmap != null) {
                            tile.bitmap.recycle();
                            tile.bitmap = null;
                        }
                    }
                } else if (tile.sampleSize == fullImageSampleSize) {
                    tile.visible = true;
                }
            }
        }

    }

    /**
     * Determine whether tile is visible.
     */
    private boolean tileVisible(Tile tile) {
        float sVisLeft = viewToSourceX(0),
            sVisRight = viewToSourceX(getWidth()),
            sVisTop = viewToSourceY(0),
            sVisBottom = viewToSourceY(getHeight());
        return !(sVisLeft > tile.sRect.right || tile.sRect.left > sVisRight || sVisTop > tile.sRect.bottom || tile.sRect.top > sVisBottom);
    }

    /**
     * Calculates sample size to fit the source image in given bounds.
     */
    private int calculateInSampleSize() {
        float adjustedScale = scale;
        if (minimumTileDpi > 0) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            float averageDpi = (metrics.xdpi + metrics.ydpi)/2;
            adjustedScale = (minimumTileDpi/averageDpi) * scale;
        }

        int reqWidth = (int)(rotatedSourceWidth() * adjustedScale);
        int reqHeight = (int)(rotatedSourceHeight() * adjustedScale);

        // Raw height and width of image
        int inSampleSize = 1;
        if (reqWidth == 0 || reqHeight == 0) {
            return 32;
        }

        if (rotatedSourceHeight() > reqHeight || rotatedSourceWidth() > reqWidth) {

            // Calculate ratios of height and width to requested height and width
            final int heightRatio = Math.round((float) rotatedSourceHeight() / (float) reqHeight);
            final int widthRatio = Math.round((float) rotatedSourceWidth() / (float) reqWidth);

            // Choose the smallest ratio as inSampleSize value, this will guarantee
            // a final image with both dimensions larger than or equal to the
            // requested height and width.
            inSampleSize = heightRatio < widthRatio ? heightRatio : widthRatio;
        }

        // We want the actual sample size that will be used, so round down to nearest power of 2.
        int power = 1;
        while (power * 2 < inSampleSize) {
            power = power * 2;
        }

        return power;
    }

    /**
     * Once source image and view dimensions are known, creates a map of sample size to tile grid.
     */
    private void initialiseTileMap(Point maxTileDimensions) {
        this.tileMap = new LinkedHashMap<Integer, List<Tile>>();
        int sampleSize = fullImageSampleSize;
        int xTiles = 1;
        int yTiles = 1;
        while (true) {
            int sTileWidth = rotatedSourceWidth()/xTiles;
            int sTileHeight = rotatedSourceHeight()/yTiles;
            int subTileWidth = sTileWidth/sampleSize;
            int subTileHeight = sTileHeight/sampleSize;
            while (subTileWidth > maxTileDimensions.x || (subTileWidth > getWidth() * 1.25 && sampleSize < fullImageSampleSize)) {
                xTiles += 1;
                sTileWidth = rotatedSourceWidth()/xTiles;
                subTileWidth = sTileWidth/sampleSize;
            }
            while (subTileHeight > maxTileDimensions.y || (subTileHeight > getHeight() * 1.25 && sampleSize < fullImageSampleSize)) {
                yTiles += 1;
                sTileHeight = rotatedSourceHeight()/yTiles;
                subTileHeight = sTileHeight/sampleSize;
            }
            List<Tile> tileGrid = new ArrayList<Tile>(xTiles * yTiles);
            for (int x = 0; x < xTiles; x++) {
                for (int y = 0; y < yTiles; y++) {
                    Tile tile = new Tile(
                        x * sTileWidth, y * sTileHeight,
                        sTileWidth, sTileHeight,
                        sampleSize
                    );
                    tile.visible = sampleSize == fullImageSampleSize;
                    tileGrid.add(tile);
                }
            }
            tileMap.put(sampleSize, tileGrid);
            if (sampleSize == 1) {
                break;
            } else {
                sampleSize /= 2;
            }
        }
    }

    /**
     * Called by worker task when a tile has loaded. Redraws the view.
     */
    private synchronized void onTileLoaded() {
        invalidate();

        // If all base layer tiles are ready, inform subclasses the image is ready to display on next draw.
        if (!baseLayerReadySent) {
            boolean baseLayerReady = true;
            for (Map.Entry<Integer, List<Tile>> tileMapEntry : tileMap.entrySet()) {
                if (tileMapEntry.getKey() == fullImageSampleSize) {
                    for (Tile tile : tileMapEntry.getValue()) {
                        if (tile.loading || tile.bitmap == null) {
                            baseLayerReady = false;
                        }
                    }
                }
            }
            if (baseLayerReady) {
                baseLayerReadySent = true;
                onBaseLayerReady();
                if (tileLoaderListener != null) {
                    tileLoaderListener.onBaseLayerReady();
                }
            }
        }
    }

    /**
     * Async task used to load images without blocking the UI thread.
     */
    private static class BitmapTileTask extends AsyncTask<Void, Void, Bitmap> {
        private final WeakReference<SubsamplingScaleImageView> viewRef;
        private final WeakReference<ImageRegionDecoder> decoderRef;
        private final WeakReference<Tile> tileRef;
        private Exception exception;

        public BitmapTileTask(SubsamplingScaleImageView view, ImageRegionDecoder decoder, Tile tile) {
            this.viewRef = new WeakReference<SubsamplingScaleImageView>(view);
            this.decoderRef = new WeakReference<ImageRegionDecoder>(decoder);
            this.tileRef = new WeakReference<Tile>(tile);
            tile.loading = true;
        }

        @Override
        protected Bitmap doInBackground(Void... params) {
            try {
                final ImageRegionDecoder decoder = decoderRef.get();
                final Tile tile = tileRef.get();
                final SubsamplingScaleImageView view = viewRef.get();
                if (decoder != null && tile != null && view != null && decoder.isReady()) {
                    synchronized (view.decoderLock) {
                        // Update tile's file sRect according to rotation
                        view.fileSRect(tile.sRect, tile.fileSRect);
                        Bitmap bitmap = decoder.decodeRegion(tile.fileSRect, tile.sampleSize);
                        int rotation = view.getRequiredRotation();
                        if (rotation != 0) {
                            Matrix matrix = new Matrix();
                            matrix.postRotate(rotation);
                            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                        }
                        return bitmap;
                    }
                } else if (tile != null) {
                    tile.loading = false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to decode tile", e);
                this.exception = e;
                final SubsamplingScaleImageView subsamplingScaleImageView = viewRef.get();
                if (subsamplingScaleImageView != null && subsamplingScaleImageView.getTileImageLoaderListener() != null) {
                    subsamplingScaleImageView.getTileImageLoaderListener().onTileLoadError(e);
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            final SubsamplingScaleImageView subsamplingScaleImageView = viewRef.get();
            final Tile tile = tileRef.get();
            if (subsamplingScaleImageView != null && tile != null) {
                if (bitmap != null) {
                    tile.bitmap = bitmap;
                    tile.loading = false;
                    subsamplingScaleImageView.onTileLoaded();
                } else if (exception != null) {
                    final TileImageLoaderListener listener = subsamplingScaleImageView.getTileImageLoaderListener();
                    if (listener != null) {
                        listener.onTileLoadError(exception);
                    }
                }
            }
        }
    }

    /**
     * In SDK 14 and above, use canvas max bitmap width and height instead of the default 2048, to avoid redundant tiling.
     */
    private Point getMaxBitmapDimensions(Canvas canvas) {
        if (VERSION.SDK_INT >= 14) {
            // TODO don't use reflection
            try {
                int maxWidth = (Integer)Canvas.class.getMethod("getMaximumBitmapWidth").invoke(canvas);
                int maxHeight = (Integer)Canvas.class.getMethod("getMaximumBitmapHeight").invoke(canvas);
                return new Point(maxWidth, maxHeight);
            } catch (Exception e) {
                // Return default
            }
        }
        return new Point(2048, 2048);
    }

    /**
     * Converts source rectangle from tile, which treats the image file as if it were in the correct orientation already,
     * to the rectangle of the image that needs to be loaded.
     */
    @SuppressWarnings("SuspiciousNameCombination")
    private void fileSRect(Rect sRect, Rect target) {
        if (getRequiredRotation() == 0) {
            target.set(sRect);
        } else if (getRequiredRotation() == 90) {
            target.set(sRect.top, getSourceHeight() - sRect.right, sRect.bottom, getSourceHeight() - sRect.left);
        } else if (getRequiredRotation() == 180) {
            target.set(getSourceWidth() - sRect.right, getSourceHeight() - sRect.bottom, getSourceWidth() - sRect.left, getSourceHeight() - sRect.top);
        } else {
            target.set(getSourceWidth() - sRect.bottom, sRect.left, getSourceWidth() - sRect.top, sRect.right);
        }
    }

    /**
     * Swap the default decoder implementation for one of your own. You must do this before setting the image file or
     * asset, and you cannot use a custom decoder when using layout XML to set an asset name. Your class must have a
     * public default constructor.
     * @param decoderClass The {@link ImageRegionDecoder} implementation to use.
     */
    public final void setDecoderClass(Class<? extends ImageRegionDecoder> decoderClass) {
        if (decoderClass == null) {
            throw new IllegalArgumentException("Decoder class cannot be set to null");
        }
        this.decoderClass = decoderClass;
    }

     /**
     * By default, image tiles are at least as high resolution as the screen. For a retina screen this may not be
     * necessary, and may increase the likelihood of an OutOfMemoryError. This method sets a DPI at which higher
     * resolution tiles should be loaded. Using a lower number will on average use less memory but result in a lower
     * quality image. 160-240dpi will usually be enough. This should be called before setting the image source,
     * because it affects which tiles get loaded.
     * @param minimumTileDpi Tile loading threshold.
     */
    public void setMinimumTileDpi(int minimumTileDpi) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float averageDpi = (metrics.xdpi + metrics.ydpi)/2;
        this.minimumTileDpi = (int)Math.min(averageDpi, minimumTileDpi);
        if (isImageReady()) {
            reset(false);
            invalidate();
        }
    }

     /**
     * Subclasses can override this method to be informed when the base layer tiles have been loaded -
     * this is called immediately before the view draws them. You can also use an {@link OnImageEventListener}
     * to receive notification of this event.
     */
    protected void onBaseLayerReady() { }

    /**
     * Call to find whether the base layer tiles have been loaded. Before this event the view is blank.
     */
    public final boolean isBaseLayerReady() {
        return baseLayerReadySent;
    }

    public TileImageLoaderListener getTileImageLoaderListener() {
        return tileLoaderListener;
    }

    public void setTileImageLoaderListener(TileImageLoaderListener listener) {
        this.tileLoaderListener = listener;
    }

    /**
     * Add a listener allowing notification of load and error events.
     * @deprecated Use {@link #setTileImageLoaderListener(TileImageLoaderListener)} and {@link #setImageSizeDecoderListener(ImageSizeDecoderListener)} instead.
     */
    public void setOnImageEventListener(OnImageEventListener onImageEventListener) {
        setImageSizeDecoderListener(new DeprecatedListenerWrapper(onImageEventListener));
        setTileImageLoaderListener(onImageEventListener);
    }

    /**
     * An event listener, allowing subclasses and activities to be notified of significant events.
     * @deprecated Use {@link com.davemorrissey.labs.subscaleview.ImageSizeDecoderListener} and {@link com.davemorrissey.labs.subscaleview.TileImageLoaderListener} instead.
     */
    public static interface OnImageEventListener extends DeprecatedImageEventListener, TileImageLoaderListener {
    }

    /**
     * Default implementation of {@link OnImageEventListener} for extension. This does nothing in any method.
     * @deprecated Do not use this class in new code.
     */
    public class DefaultOnImageEventListener implements OnImageEventListener {

        @Override public void onImageReady() { }
        @Override public void onBaseLayerReady() { }
        @Override public void onInitialisationError(Exception e) { }
        @Override public void onTileLoadError(Exception e) { }

    }
}
