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
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.AttributeSet;
import android.util.Log;

import com.davemorrissey.labs.subscaleview.R.styleable;
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder;
import com.davemorrissey.labs.subscaleview.decoder.SkiaImageDecoder;

import java.lang.ref.WeakReference;

/**
 * An alternative to {@link SubsamplingScaleImageView}, this reproduces all the same event handling, animation and
 * configuration, but does not subsample or tile images. This is suitable for use when you know the dimensions of the
 * image to be displayed, the image is no more than 2048px wide or tall, and not large enough to cause any target device
 * to run out of memory. This is provided to allow display of {@link Bitmap} objects, and images from resources, and to
 * support older Android versions.
 *
 * v prefixes - coordinates, translations and distances measured in screen (view) pixels
 * s prefixes - coordinates, translations and distances measured in source image pixels (scaled)
 */
public class ScaleImageView extends ScaleImageViewBase<Bitmap> {

    private static final String TAG = ScaleImageView.class.getSimpleName();

    // Image decoder class
    private Class<? extends ImageDecoder> decoderClass = SkiaImageDecoder.class;

    // Volatile fields used to reduce object creation
    private Matrix matrix;
    private RectF sRect;

    public ScaleImageView(Context context, AttributeSet attr) {
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

    public ScaleImageView(Context context) {
        this(context, null);
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
     * Display a {@link Bitmap}.
     * @param bitmap Bitmap to be displayed.
     */
    public final void setImageBitmap(Bitmap bitmap) {
        setImageBitmap(bitmap, null);
    }

    /**
     * Display a {@link Bitmap}, starting with a given orientation setting, scale and center.
     * @param bitmap Bitmap to be displayed.
     * @param state State to be restored. Nullable.
     */
    public final void setImageBitmap(Bitmap bitmap, ImageViewState state) {
        reset(true);
        restoreState(state);
        onImageSourceAvailable(bitmap, bitmap.getWidth(), bitmap.getHeight(), ORIENTATION_0);
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
        BitmapInitTask task = new BitmapInitTask(this, getContext(), decoderClass, uri);
        task.execute();
        invalidate();
    }

    /**
     * Reset all state before setting/changing image or setting new rotation.
     */
    protected void reset(boolean newImage) {
        super.reset(newImage);
        matrix = null;
        sRect = null;
        if (newImage) {
            setSourceSize(0, 0);
            sourceOrientation = 0;
        }
    }

    @Override
    protected void discardImageDataSource() {
        imageDataSource.recycle();
        imageDataSource = null;
    }

    @Override
    protected void drawImageData(Canvas canvas) {
        if (matrix == null) { matrix = new Matrix(); }
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postRotate(getOrientation());
        matrix.postTranslate(vTranslate.x, vTranslate.y);

        // TODO fix this mess
        if (getOrientation() == ORIENTATION_180) {
            matrix.postTranslate(scale * getSourceWidth(), scale * getSourceHeight());
        } else if (getOrientation() == ORIENTATION_90) {
            matrix.postTranslate(scale * getSourceHeight(), 0);
        } else if (getOrientation() == ORIENTATION_270) {
            matrix.postTranslate(0, scale * getSourceWidth());
        }

        if (getBackgroundPaint() != null) {
            if (sRect == null) { sRect = new RectF(); }
            sRect.set(0f, 0f, getSourceWidth(), getSourceHeight());
            matrix.mapRect(sRect);
            canvas.drawRect(sRect, getBackgroundPaint());
        }
        canvas.drawBitmap(imageDataSource, matrix, getBitmapPaint());
    }

    /**
     * Async task used to load bitmap without blocking the UI thread.
     */
    private static class BitmapInitTask extends AsyncTask<Void, Void, int[]> {
        private final WeakReference<ScaleImageView> viewRef;
        private final WeakReference<Context> contextRef;
        private final WeakReference<Class<? extends ImageDecoder>> decoderClassRef;
        private final Uri source;
        private Bitmap bitmap;
        private Exception exception;

        public BitmapInitTask(ScaleImageView view, Context context, Class<? extends ImageDecoder> decoderClass, Uri source) {
            this.viewRef = new WeakReference<ScaleImageView>(view);
            this.contextRef = new WeakReference<Context>(context);
            this.decoderClassRef = new WeakReference<Class<? extends ImageDecoder>>(decoderClass);
            this.source = source;
        }

        @Override
        protected int[] doInBackground(Void... params) {
            try {
                String sourceUri = source.toString();
                Context context = contextRef.get();
                Class<? extends ImageDecoder> decoderClass = decoderClassRef.get();
                if (context != null && decoderClass != null) {
                    int exifOrientation = ORIENTATION_0;
                    bitmap = decoderClass.newInstance().decode(context, source);
                    if (sourceUri.startsWith(FILE_SCHEME) && !sourceUri.startsWith(ASSET_SCHEME)) {
                        try {
                            ExifInterface exifInterface = new ExifInterface(sourceUri.substring(FILE_SCHEME.length() - 1));
                            int orientationAttr = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                            if (orientationAttr == ExifInterface.ORIENTATION_NORMAL || orientationAttr == ExifInterface.ORIENTATION_UNDEFINED) {
                                exifOrientation = ORIENTATION_0;
                            } else if (orientationAttr == ExifInterface.ORIENTATION_ROTATE_90) {
                                exifOrientation = ORIENTATION_90;
                            } else if (orientationAttr == ExifInterface.ORIENTATION_ROTATE_180) {
                                exifOrientation = ORIENTATION_180;
                            } else if (orientationAttr == ExifInterface.ORIENTATION_ROTATE_270) {
                                exifOrientation = ORIENTATION_270;
                            } else {
                                Log.w(TAG, "Unsupported EXIF orientation: " + orientationAttr);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Could not get EXIF orientation of image");
                        }
                    }
                    return new int[] { bitmap.getWidth(), bitmap.getHeight(), exifOrientation };
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialise bitmap decoder", e);
                this.exception = e;
            }
            return null;
        }

        @Override
        protected void onPostExecute(int[] xyo) {
            final ScaleImageView scaleImageView = viewRef.get();
            if (scaleImageView != null) {
                if (bitmap != null && xyo != null && xyo.length == 3) {
                    scaleImageView.onImageSourceAvailable(bitmap, xyo[0], xyo[1], xyo[2]);
                } else if (exception != null ) {
                    final ImageSizeDecoderListener listener = scaleImageView.getImageSizeDecoderListener();
                    if (listener != null) {
                        listener.onImageSizeDecodingFailed(exception);
                    }
                }
            }
        }
    }

    /**
     * Swap the default decoder implementation for one of your own. You must do this before setting the image file or
     * asset, and you cannot use a custom decoder when using layout XML to set an asset name. Your class must have a
     * public default constructor.
     * @param decoderClass The {@link ImageDecoder} implementation to use.
     */
    public final void setDecoderClass(Class<? extends ImageDecoder> decoderClass) {
        if (decoderClass == null) {
            throw new IllegalArgumentException("Decoder class cannot be set to null");
        }
        this.decoderClass = decoderClass;
    }

    @Override
    protected void preloadInitialImageData(Canvas canvas) { }

    @Override
    protected void refreshImageData(boolean loadIfNecessary) { }

    @Override
    protected boolean isInitialImageDataLoaded() {
        return true;
    }

    /**
     * Add a listener allowing notification of load and error events.
     */
    public void setOnImageEventListener(OnImageEventListener onImageEventListener) {
        setImageSizeDecoderListener(new DeprecatedListenerWrapper(onImageEventListener));
    }

    /**
     * Builder class used to set additional options for a scale animation. Create an instance using {@link #animateScale(float)},
     * then set your options and call {@link #start()}.
     */

    /**
     * An event listener, allowing subclasses and activities to be notified of significant events.
     * @deprecated Use {@link com.davemorrissey.labs.subscaleview.ImageSizeDecoderListener} instead.
     */
    public interface OnImageEventListener extends DeprecatedImageEventListener {
    }

    /**
     * Default implementation of {@link OnImageEventListener} for extension. This does nothing in any method.
     * @deprecated Do not use this class in new code.
     */
    public class DefaultOnImageEventListener implements OnImageEventListener {

        @Override public void onImageReady() { }
        @Override public void onInitialisationError(Exception e) { }
    }
}
