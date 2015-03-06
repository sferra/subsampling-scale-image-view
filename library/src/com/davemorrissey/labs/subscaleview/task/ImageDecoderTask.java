package com.davemorrissey.labs.subscaleview.task;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;

import com.davemorrissey.labs.subscaleview.DeprecatedConstants;
import com.davemorrissey.labs.subscaleview.ImageSizeDecoderListener;
import com.davemorrissey.labs.subscaleview.ScaleImageView;
import com.davemorrissey.labs.subscaleview.ScaleImageViewBase;
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder;

import java.lang.ref.WeakReference;

public class ImageDecoderTask extends AsyncTask<Void, Void, int[]> {
    private static final String TAG = ImageDecoderTask.class.getSimpleName();
    private final WeakReference<ScaleImageView> viewRef;
    private final WeakReference<Context> contextRef;
    private final WeakReference<Class<? extends ImageDecoder>> decoderClassRef;
    private final Uri source;
    private Bitmap bitmap;
    private Exception exception;

    public ImageDecoderTask(ScaleImageView view, Context context, Class<? extends ImageDecoder> decoderClass, Uri source) {
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
                int exifOrientation = DeprecatedConstants.ORIENTATION_0;
                bitmap = decoderClass.newInstance().decode(context, source);
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
