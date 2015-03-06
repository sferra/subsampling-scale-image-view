package com.davemorrissey.labs.subscaleview.task;

import android.content.Context;
import android.graphics.Point;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;

import com.davemorrissey.labs.subscaleview.DeprecatedConstants;
import com.davemorrissey.labs.subscaleview.ImageSizeDecoderListener;
import com.davemorrissey.labs.subscaleview.ScaleImageViewBase;
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder;

import java.lang.ref.WeakReference;

public class ImageRegionDecoderTask extends AsyncTask<Void, Void, int[]> {
    private static final String TAG = ImageRegionDecoderTask.class.getSimpleName();
    private final WeakReference<ScaleImageViewBase<ImageRegionDecoder>> viewRef;
    private final WeakReference<Context> contextRef;
    private final WeakReference<Class<? extends ImageRegionDecoder>> decoderClassRef;
    private final Uri source;
    private ImageRegionDecoder decoder;
    private Exception exception;

    public ImageRegionDecoderTask(ScaleImageViewBase<ImageRegionDecoder> view, Context context, Class<? extends ImageRegionDecoder> decoderClass, Uri source) {
        this.viewRef = new WeakReference<ScaleImageViewBase<ImageRegionDecoder>>(view);
        this.contextRef = new WeakReference<Context>(context);
        this.decoderClassRef = new WeakReference<Class<? extends ImageRegionDecoder>>(decoderClass);
        this.source = source;
    }

    @Override
    protected int[] doInBackground(Void... params) {
        try {
            String sourceUri = source.toString();
            Context context = contextRef.get();
            Class<? extends ImageRegionDecoder> decoderClass = decoderClassRef.get();
            if (context != null && decoderClass != null) {
                int exifOrientation = DeprecatedConstants.ORIENTATION_0;
                decoder = decoderClass.newInstance();
                Point dimensions = decoder.init(context, source);
                if (sourceUri.startsWith(ScaleImageViewBase.FILE_SCHEME) && !sourceUri.startsWith(ScaleImageViewBase.ASSET_SCHEME)) {
                    try {
                        ExifInterface exifInterface = new ExifInterface(sourceUri.substring(ScaleImageViewBase.FILE_SCHEME.length() - 1));
                        int orientationAttr = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                        if (orientationAttr == ExifInterface.ORIENTATION_NORMAL || orientationAttr == ExifInterface.ORIENTATION_UNDEFINED) {
                            exifOrientation = DeprecatedConstants.ORIENTATION_0;
                        } else if (orientationAttr == ExifInterface.ORIENTATION_ROTATE_90) {
                            exifOrientation = DeprecatedConstants.ORIENTATION_90;
                        } else if (orientationAttr == ExifInterface.ORIENTATION_ROTATE_180) {
                            exifOrientation = DeprecatedConstants.ORIENTATION_180;
                        } else if (orientationAttr == ExifInterface.ORIENTATION_ROTATE_270) {
                            exifOrientation = DeprecatedConstants.ORIENTATION_270;
                        } else {
                            Log.w(TAG, "Unsupported EXIF orientation: " + orientationAttr);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not get EXIF orientation of image");
                    }
                }
                return new int[] { dimensions.x, dimensions.y, exifOrientation };
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialise bitmap decoder", e);
            this.exception = e;
        }
        return null;
    }

    @Override
    protected void onPostExecute(int[] xyo) {
        final ScaleImageViewBase<ImageRegionDecoder> view = viewRef.get();
        if (view != null) {
            if (decoder != null && xyo != null && xyo.length == 3) {
                view.onImageSourceAvailable(decoder, xyo[0], xyo[1], xyo[2]);
            } else if (exception != null) {
                final ImageSizeDecoderListener listener = view.getImageSizeDecoderListener();
                if (listener != null) {
                    listener.onImageSizeDecodingFailed(exception);
                }
            }
        }
    }
}
