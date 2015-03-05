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
import android.graphics.Paint.Style;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.FloatMath;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;

import com.davemorrissey.labs.subscaleview.R.styleable;
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder;
import com.davemorrissey.labs.subscaleview.decoder.SkiaImageRegionDecoder;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
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
@SuppressWarnings("unused")
public class SubsamplingScaleImageView extends ScaleImageViewBase {

    private static final String TAG = SubsamplingScaleImageView.class.getSimpleName();

    /** During zoom animation, keep the point of the image that was tapped in the same place, and scale the image around it. */
    public static final int ZOOM_FOCUS_FIXED = 1;
    /** During zoom animation, move the point of the image that was tapped to the center of the screen. */
    public static final int ZOOM_FOCUS_CENTER = 2;
    /** Zoom in to and center the tapped point immediately without animating. */
    public static final int ZOOM_FOCUS_CENTER_IMMEDIATE = 3;

    private static final List<Integer> VALID_ZOOM_STYLES = Arrays.asList(ZOOM_FOCUS_FIXED, ZOOM_FOCUS_CENTER, ZOOM_FOCUS_CENTER_IMMEDIATE);

    /** Quadratic ease out. Not recommended for scale animation, but good for panning. */
    public static final int EASE_OUT_QUAD = 1;
    /** Quadratic ease in and out. */
    public static final int EASE_IN_OUT_QUAD = 2;

    private static final List<Integer> VALID_EASING_STYLES = Arrays.asList(EASE_IN_OUT_QUAD, EASE_OUT_QUAD);

    /** Don't allow the image to be panned off screen. As much of the image as possible is always displayed, centered in the view when it is smaller. This is the best option for galleries. */
    public static final int PAN_LIMIT_INSIDE = 1;
    /** Allows the image to be panned until it is just off screen, but no further. The edge of the image will stop when it is flush with the screen edge. */
    public static final int PAN_LIMIT_OUTSIDE = 2;
    /** Allows the image to be panned until a corner reaches the center of the screen but no further. Useful when you want to pan any spot on the image to the exact center of the screen. */
    public static final int PAN_LIMIT_CENTER = 3;

    private static final List<Integer> VALID_PAN_LIMITS = Arrays.asList(PAN_LIMIT_INSIDE, PAN_LIMIT_OUTSIDE, PAN_LIMIT_CENTER);

    /** Scale the image so that both dimensions of the image will be equal to or less than the corresponding dimension of the view. The image is then centered in the view. This is the default behaviour and best for galleries. */
    public static final int SCALE_TYPE_CENTER_INSIDE = 1;
    /** Scale the image uniformly so that both dimensions of the image will be equal to or larger than the corresponding dimension of the view. The image is then centered in the view. */
    public static final int SCALE_TYPE_CENTER_CROP = 2;
    /** Scale the image so that both dimensions of the image will be equal to or less than the maxScale and equal to or larger than minScale. The image is then centered in the view. */
    public static final int SCALE_TYPE_CUSTOM = 3;

    private static final List<Integer> VALID_SCALE_TYPES = Arrays.asList(SCALE_TYPE_CENTER_CROP, SCALE_TYPE_CENTER_INSIDE, SCALE_TYPE_CUSTOM);

    // Overlay tile boundaries and other info
    private boolean debug = false;

    // Max scale allowed (prevent infinite zoom)
    private float maxScale = 2F;

    // Min scale allowed (prevent infinite zoom)
    private float minScale = minScale();

    // Density to reach before loading higher resolution tiles
    private int minimumTileDpi = -1;

    // Pan limiting style
    private int panLimit = PAN_LIMIT_INSIDE;

    // Minimum scale type
    private int minimumScaleType = SCALE_TYPE_CENTER_INSIDE;

    // Gesture detection settings
    private boolean panEnabled = true;
    private boolean zoomEnabled = true;
    private boolean quickScaleEnabled = true;

    // Double tap zoom behaviour
    private float doubleTapZoomScale = 1F;
    private int doubleTapZoomStyle = ZOOM_FOCUS_FIXED;

    // Current scale and scale at start of zoom
    private float scale;
    private float scaleStart;

    // Screen coordinate of top-left corner of source image
    private PointF vTranslate;
    private PointF vTranslateStart;

    // Source coordinate to center on, used when new position is set externally before view is ready
    private Float pendingScale;
    private PointF sPendingCenter;
    private PointF sRequestedCenter;

    // Is two-finger zooming in progress
    private boolean isZooming;
    // Is one-finger panning in progress
    private boolean isPanning;
    // Is quick-scale gesture in progress
    private boolean isQuickScaling;
    // Max touches used in current gesture
    private int maxTouchCount;

    // Fling detector
    private GestureDetector detector;

    // Tile decoder
    private ImageRegionDecoder decoder;
    private Class<? extends ImageRegionDecoder> decoderClass = SkiaImageRegionDecoder.class;
    private final Object decoderLock = new Object();

    // Sample size used to display the whole image when fully zoomed out
    private int fullImageSampleSize;

    // Map of zoom level to tile grid
    private Map<Integer, List<Tile>> tileMap;

    // Debug values
    private PointF vCenterStart;
    private float vDistStart;

    // Current quickscale state
    private final float quickScaleThreshold;
    private PointF quickScaleCenter;
    private float quickScaleLastDistance;
    private PointF quickScaleLastPoint;
    private boolean quickScaleMoved;

    // Scale and center animation tracking
    private Anim anim;

    // Whether a ready notification has been sent to subclasses
    private boolean dimensionsReadySent = false;
    // Whether a base layer loaded notification has been sent to subclasses
    private boolean baseLayerReadySent = false;

    // Event listener
    private OnImageEventListener onImageEventListener;

    // Long click listener
    private OnLongClickListener onLongClickListener;

    // Long click handler
    private Handler handler;
    private static final int MESSAGE_LONG_CLICK = 1;

    // Paint objects created once and reused for efficiency
    private Paint bitmapPaint;
    private Paint debugPaint;
    private Paint tileBgPaint;

    // Volatile fields used to reduce object creation
    private ScaleAndTranslate satTemp;

    public SubsamplingScaleImageView(Context context, AttributeSet attr) {
        super(context, attr);
        setMinimumDpi(160);
        setDoubleTapZoomDpi(160);
        setGestureDetector(context);
        this.handler = new Handler(new Handler.Callback() {
            public boolean handleMessage(Message message) {
                if (message.what == MESSAGE_LONG_CLICK && onLongClickListener != null) {
                    maxTouchCount = 0;
                    SubsamplingScaleImageView.super.setOnLongClickListener(onLongClickListener);
                    performLongClick();
                    SubsamplingScaleImageView.super.setOnLongClickListener(null);
                }
                return true;
            }
        });
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

        quickScaleThreshold = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, context.getResources().getDisplayMetrics());
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
        BitmapInitTask task = new BitmapInitTask(this, getContext(), decoderClass, uri);
        task.execute();
        invalidate();
    }

    /**
     * Reset all state before setting/changing image or setting new rotation.
     */
    protected void reset(boolean newImage) {
        scale = 0f;
        scaleStart = 0f;
        vTranslate = null;
        vTranslateStart = null;
        pendingScale = 0f;
        sPendingCenter = null;
        sRequestedCenter = null;
        isZooming = false;
        isPanning = false;
        isQuickScaling = false;
        maxTouchCount = 0;
        fullImageSampleSize = 0;
        vCenterStart = null;
        vDistStart = 0;
        quickScaleCenter = null;
        quickScaleLastDistance = 0f;
        quickScaleLastPoint = null;
        quickScaleMoved = false;
        anim = null;
        satTemp = null;
        if (newImage) {
            if (decoder != null) {
                synchronized (decoderLock) {
                    decoder.recycle();
                    decoder = null;
                }
            }
            setSourceSize(0, 0);
            sourceOrientation = 0;
            dimensionsReadySent = false;
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
        setGestureDetector(getContext());
    }

    private void setGestureDetector(final Context context) {
        this.detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (panEnabled && dimensionsReadySent && vTranslate != null && e1 != null && e2 != null && (Math.abs(e1.getX() - e2.getX()) > 50 || Math.abs(e1.getY() - e2.getY()) > 50) && (Math.abs(velocityX) > 500 || Math.abs(velocityY) > 500) && !isZooming) {
                    PointF vTranslateEnd = new PointF(vTranslate.x + (velocityX * 0.25f), vTranslate.y + (velocityY * 0.25f));
                    float sCenterXEnd = ((getWidth()/2) - vTranslateEnd.x)/scale;
                    float sCenterYEnd = ((getHeight()/2) - vTranslateEnd.y)/scale;
                    new AnimationBuilder(new PointF(sCenterXEnd, sCenterYEnd)).withEasing(EASE_OUT_QUAD).withPanLimited(false).start();
                    return true;
                }
                return super.onFling(e1, e2, velocityX, velocityY);
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                performClick();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (zoomEnabled && dimensionsReadySent && vTranslate != null) {
                    if (quickScaleEnabled) {
                        vCenterStart = new PointF(e.getX(), e.getY());
                        vTranslateStart = new PointF(vTranslate.x, vTranslate.y);
                        scaleStart = scale;

                        isQuickScaling = true;
                        isZooming = true;
                        quickScaleCenter = viewToSourceCoord(vCenterStart);
                        quickScaleLastDistance = -1F;
                        quickScaleLastPoint = new PointF(quickScaleCenter.x, quickScaleCenter.y);
                        quickScaleMoved = false;

                        setGestureDetector(context);

                        // We really want to get events in onTouchEvent after this, so don't return true
                        return false;
                    }

                    float doubleTapZoomScale = Math.min(maxScale, SubsamplingScaleImageView.this.doubleTapZoomScale);
                    boolean zoomIn = scale <= doubleTapZoomScale * 0.9;
                    float targetScale = zoomIn ? doubleTapZoomScale : minScale();
                    PointF targetSCenter = viewToSourceCoord(e.getX(), e.getY());
                    if (doubleTapZoomStyle == ZOOM_FOCUS_CENTER_IMMEDIATE) {
                        setScaleAndCenter(targetScale, targetSCenter);
                    } else if (doubleTapZoomStyle == ZOOM_FOCUS_CENTER || !zoomIn) {
                        new AnimationBuilder(targetScale, targetSCenter).withInterruptible(false).start();
                    } else if (doubleTapZoomStyle == ZOOM_FOCUS_FIXED) {
                        new AnimationBuilder(targetScale, targetSCenter, new PointF(e.getX(), e.getY())).withInterruptible(false).start();
                    }

                    // Hacky solution for #15 - after a double tap the GestureDetector gets in a state where the next
                    // fling is ignored, so here we replace it with a new one.
                    setGestureDetector(context);

                    invalidate();
                    return true;
                }
                return super.onDoubleTapEvent(e);
            }
        });
    }

    /**
     * On resize, preserve center and scale. Various behaviours are possible, override this method to use another.
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (dimensionsReadySent) {
            setScaleAndCenter(getScale(), getCenter());
        }
    }

    /**
     * Measures the width and height of the view, preserving the aspect ratio of the image displayed if wrap_content is
     * used. The image will scale within this box, not resizing the view as it is zoomed.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
        int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
        int parentHeight = MeasureSpec.getSize(heightMeasureSpec);
        boolean resizeWidth = widthSpecMode != MeasureSpec.EXACTLY;
        boolean resizeHeight = heightSpecMode != MeasureSpec.EXACTLY;
        int width = parentWidth;
        int height = parentHeight;
        if (getSourceWidth() > 0 && getSourceHeight() > 0) {
            if (resizeWidth && resizeHeight) {
                width = sWidth();
                height = sHeight();
            } else if (resizeHeight) {
                height = (int)((((double)sHeight()/(double)sWidth()) * width));
            } else if (resizeWidth) {
                width = (int)((((double)sWidth()/(double)sHeight()) * height));
            }
        }
        width = Math.max(width, getSuggestedMinimumWidth());
        height = Math.max(height, getSuggestedMinimumHeight());
        setMeasuredDimension(width, height);
    }

    /**
     * Handle touch events. One finger pans, and two finger pinch and zoom plus panning.
     */
    @Override @SuppressWarnings("deprecation")
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        // During non-interruptible anims, ignore all touch events
        if (anim != null && !anim.interruptible) {
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        } else {
            anim = null;
        }

        // Abort if not ready
        if (vTranslate == null) {
            return true;
        }
        // Detect flings, taps and double taps
        if (!isQuickScaling && (detector == null || detector.onTouchEvent(event))) {
            isZooming = false;
            isPanning = false;
            maxTouchCount = 0;
            return true;
        }

        if (vTranslateStart == null) { vTranslateStart = new PointF(0, 0); }
        if (vCenterStart == null) { vCenterStart = new PointF(0, 0); }

        int touchCount = event.getPointerCount();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_1_DOWN:
            case MotionEvent.ACTION_POINTER_2_DOWN:
                anim = null;
                getParent().requestDisallowInterceptTouchEvent(true);
                maxTouchCount = Math.max(maxTouchCount, touchCount);
                if (touchCount >= 2) {
                    if (zoomEnabled) {
                        // Start pinch to zoom. Calculate distance between touch points and center point of the pinch.
                        float distance = distance(event.getX(0), event.getX(1), event.getY(0), event.getY(1));
                        scaleStart = scale;
                        vDistStart = distance;
                        vTranslateStart.set(vTranslate.x, vTranslate.y);
                        vCenterStart.set((event.getX(0) + event.getX(1))/2, (event.getY(0) + event.getY(1))/2);
                    } else {
                        // Abort all gestures on second touch
                        maxTouchCount = 0;
                    }
                    // Cancel long click timer
                    handler.removeMessages(MESSAGE_LONG_CLICK);
                } else if (!isQuickScaling) {
                    // Start one-finger pan
                    vTranslateStart.set(vTranslate.x, vTranslate.y);
                    vCenterStart.set(event.getX(), event.getY());

                    // Start long click timer
                    handler.sendEmptyMessageDelayed(MESSAGE_LONG_CLICK, 600);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                boolean consumed = false;
                if (maxTouchCount > 0) {
                    if (touchCount >= 2) {
                        // Calculate new distance between touch points, to scale and pan relative to start values.
                        float vDistEnd = distance(event.getX(0), event.getX(1), event.getY(0), event.getY(1));
                        float vCenterEndX = (event.getX(0) + event.getX(1))/2;
                        float vCenterEndY = (event.getY(0) + event.getY(1))/2;

                        if (zoomEnabled && (distance(vCenterStart.x, vCenterEndX, vCenterStart.y, vCenterEndY) > 5 || Math.abs(vDistEnd - vDistStart) > 5 || isPanning)) {
                            isZooming = true;
                            isPanning = true;
                            consumed = true;

                            scale = Math.min(maxScale, (vDistEnd / vDistStart) * scaleStart);

                            if (scale <= minScale()) {
                                // Minimum scale reached so don't pan. Adjust start settings so any expand will zoom in.
                                vDistStart = vDistEnd;
                                scaleStart = minScale();
                                vCenterStart.set(vCenterEndX, vCenterEndY);
                                vTranslateStart.set(vTranslate);
                            } else if (panEnabled) {
                                // Translate to place the source image coordinate that was at the center of the pinch at the start
                                // at the center of the pinch now, to give simultaneous pan + zoom.
                                float vLeftStart = vCenterStart.x - vTranslateStart.x;
                                float vTopStart = vCenterStart.y - vTranslateStart.y;
                                float vLeftNow = vLeftStart * (scale/scaleStart);
                                float vTopNow = vTopStart * (scale/scaleStart);
                                vTranslate.x = vCenterEndX - vLeftNow;
                                vTranslate.y = vCenterEndY - vTopNow;
                            } else if (sRequestedCenter != null) {
                                // With a center specified from code, zoom around that point.
                                vTranslate.x = (getWidth()/2) - (scale * sRequestedCenter.x);
                                vTranslate.y = (getHeight()/2) - (scale * sRequestedCenter.y);
                            } else {
                                // With no requested center, scale around the image center.
                                vTranslate.x = (getWidth()/2) - (scale * (sWidth()/2));
                                vTranslate.y = (getHeight()/2) - (scale * (sHeight()/2));
                            }

                            fitToBounds(true);
                            refreshRequiredTiles(false);
                        }
                    } else if (isQuickScaling) {
                        // One finger zoom
                        // Stole Google's Magical Formula™ to make sure it feels the exact same
                        float dist = Math.abs(vCenterStart.y - event.getY()) * 2 + quickScaleThreshold;

                        if (quickScaleLastDistance == -1F) quickScaleLastDistance = dist;
                        boolean isUpwards = event.getY() > quickScaleLastPoint.y;
                        quickScaleLastPoint.set(0, event.getY());

                        float spanDiff = (Math.abs(1 - (dist / quickScaleLastDistance)) * 0.5F);

                        if (spanDiff > 0.03f || quickScaleMoved) {
                            quickScaleMoved = true;

                            float multiplier = 1;
                            if (quickScaleLastDistance > 0) {
                                multiplier = isUpwards ? (1 + spanDiff) : (1 - spanDiff);
                            }

                            scale = Math.max(minScale(), Math.min(maxScale, scale * multiplier));

                            if (panEnabled) {
                                float vLeftStart = vCenterStart.x - vTranslateStart.x;
                                float vTopStart = vCenterStart.y - vTranslateStart.y;
                                float vLeftNow = vLeftStart * (scale/scaleStart);
                                float vTopNow = vTopStart * (scale/scaleStart);
                                vTranslate.x = vCenterStart.x - vLeftNow;
                                vTranslate.y = vCenterStart.y - vTopNow;
                            } else if (sRequestedCenter != null) {
                                // With a center specified from code, zoom around that point.
                                vTranslate.x = (getWidth()/2) - (scale * sRequestedCenter.x);
                                vTranslate.y = (getHeight()/2) - (scale * sRequestedCenter.y);
                            } else {
                                // With no requested center, scale around the image center.
                                vTranslate.x = (getWidth()/2) - (scale * (sWidth()/2));
                                vTranslate.y = (getHeight()/2) - (scale * (sHeight()/2));
                            }
                        }

                        quickScaleLastDistance = dist;

                        fitToBounds(true);
                        refreshRequiredTiles(false);

                        consumed = true;
                    } else if (!isZooming) {
                        // One finger pan - translate the image. We do this calculation even with pan disabled so click
                        // and long click behaviour is preserved.
                        float dx = Math.abs(event.getX() - vCenterStart.x);
                        float dy = Math.abs(event.getY() - vCenterStart.y);
                        if (dx > 5 || dy > 5 || isPanning) {
                            consumed = true;
                            vTranslate.x = vTranslateStart.x + (event.getX() - vCenterStart.x);
                            vTranslate.y = vTranslateStart.y + (event.getY() - vCenterStart.y);

                            float lastX = vTranslate.x;
                            float lastY = vTranslate.y;
                            fitToBounds(true);
                            boolean atXEdge = lastX != vTranslate.x;
                            boolean edgeXSwipe = atXEdge && dx > dy && !isPanning;
                            boolean yPan = lastY == vTranslate.y && dy > 15;
                            if (!edgeXSwipe && (!atXEdge || yPan || isPanning)) {
                                isPanning = true;
                            } else if (dx > 5) {
                                // Haven't panned the image, and we're at the left or right edge. Switch to page swipe.
                                maxTouchCount = 0;
                                handler.removeMessages(MESSAGE_LONG_CLICK);
                                getParent().requestDisallowInterceptTouchEvent(false);
                            }

                            if (!panEnabled) {
                                vTranslate.x = vTranslateStart.x;
                                vTranslate.y = vTranslateStart.y;
                                getParent().requestDisallowInterceptTouchEvent(false);
                            }

                            refreshRequiredTiles(false);
                        }
                    }
                }
                if (consumed) {
                    handler.removeMessages(MESSAGE_LONG_CLICK);
                    invalidate();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_POINTER_2_UP:
                handler.removeMessages(MESSAGE_LONG_CLICK);
                if (isQuickScaling) {
                    isQuickScaling = false;
                    if (!quickScaleMoved) {
                        float doubleTapZoomScale = Math.min(maxScale, SubsamplingScaleImageView.this.doubleTapZoomScale);
                        boolean zoomIn = scale <= doubleTapZoomScale * 0.9;
                        float targetScale = zoomIn ? doubleTapZoomScale : minScale();
                        if (doubleTapZoomStyle == ZOOM_FOCUS_CENTER_IMMEDIATE) {
                            setScaleAndCenter(targetScale, quickScaleCenter);
                        } else if (doubleTapZoomStyle == ZOOM_FOCUS_CENTER || !zoomIn) {
                            new AnimationBuilder(targetScale, quickScaleCenter).withInterruptible(false).start();
                        } else if (doubleTapZoomStyle == ZOOM_FOCUS_FIXED) {
                            new AnimationBuilder(targetScale, quickScaleCenter, vCenterStart).withInterruptible(false).start();
                        }
                        invalidate();
                    }
                }
                if (maxTouchCount > 0 && (isZooming || isPanning)) {
                    if (isZooming && touchCount == 2) {
                        // Convert from zoom to pan with remaining touch
                        isPanning = true;
                        vTranslateStart.set(vTranslate.x, vTranslate.y);
                        if (event.getActionIndex() == 1) {
                            vCenterStart.set(event.getX(0), event.getY(0));
                        } else {
                            vCenterStart.set(event.getX(1), event.getY(1));
                        }
                    }
                    if (touchCount < 3) {
                        // End zooming when only one touch point
                        isZooming = false;
                    }
                    if (touchCount < 2) {
                        // End panning when no touch points
                        isPanning = false;
                        maxTouchCount = 0;
                    }
                    // Trigger load of tiles now required
                    refreshRequiredTiles(true);
                    return true;
                }
                if (touchCount == 1) {
                    isZooming = false;
                    isPanning = false;
                    maxTouchCount = 0;
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    /**
     * Draw method should not be called until the view has dimensions so the first calls are used as triggers to calculate
     * the scaling and tiling required. Once the view is setup, tiles are displayed as they are loaded.
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        createPaints();

        // If image or view dimensions are not known yet, abort.
        if (getSourceWidth() == 0 || getSourceHeight() == 0 || decoder == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }

        // On first render with no tile map ready, initialise it and kick off async base image loading.
        if (tileMap == null) {
            initialiseBaseLayer(getMaxBitmapDimensions(canvas));
            return;
        }

        // Set scale and translate before draw.
        preDraw();

        // If animating scale, calculate current scale and center with easing equations
        if (anim != null) {
            long scaleElapsed = System.currentTimeMillis() - anim.time;
            boolean finished = scaleElapsed > anim.duration;
            scaleElapsed = Math.min(scaleElapsed, anim.duration);
            scale = ease(anim.easing, scaleElapsed, anim.scaleStart, anim.scaleEnd - anim.scaleStart, anim.duration);

            // Apply required animation to the focal point
            float vFocusNowX = ease(anim.easing, scaleElapsed, anim.vFocusStart.x, anim.vFocusEnd.x - anim.vFocusStart.x, anim.duration);
            float vFocusNowY = ease(anim.easing, scaleElapsed, anim.vFocusStart.y, anim.vFocusEnd.y - anim.vFocusStart.y, anim.duration);
            // Find out where the focal point is at this scale and adjust its position to follow the animation path
            vTranslate.x -= sourceToViewX(anim.sCenterEnd.x) - vFocusNowX;
            vTranslate.y -= sourceToViewY(anim.sCenterEnd.y) - vFocusNowY;

            // For translate anims, showing the image non-centered is never allowed, for scaling anims it is during the animation.
            fitToBounds(finished || (anim.scaleStart == anim.scaleEnd));
            refreshRequiredTiles(finished);
            if (finished) {
                anim = null;
            }
            invalidate();
        }

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
        for (Map.Entry<Integer, List<Tile>> tileMapEntry : tileMap.entrySet()) {
            if (tileMapEntry.getKey() == sampleSize || hasMissingTiles) {
                for (Tile tile : tileMapEntry.getValue()) {
                    sourceToViewRect(tile.sRect, tile.vRect);
                    if (!tile.loading && tile.bitmap != null) {
                        if (tileBgPaint != null) {
                            canvas.drawRect(tile.vRect, tileBgPaint);
                        }
                        canvas.drawBitmap(tile.bitmap, null, tile.vRect, bitmapPaint);
                        if (debug) {
                            canvas.drawRect(tile.vRect, debugPaint);
                        }
                    } else if (tile.loading && debug) {
                        canvas.drawText("LOADING", tile.vRect.left + 5, tile.vRect.top + 35, debugPaint);
                    }
                    if (tile.visible && debug) {
                        canvas.drawText("ISS " + tile.sampleSize + " RECT " + tile.sRect.top + "," + tile.sRect.left + "," + tile.sRect.bottom + "," + tile.sRect.right, tile.vRect.left + 5, tile.vRect.top + 15, debugPaint);
                    }
                }
            }
        }

        if (debug) {
            canvas.drawText("Scale: " + String.format("%.2f", scale), 5, 15, debugPaint);
            canvas.drawText("Translate: " + String.format("%.2f", vTranslate.x) + ":" + String.format("%.2f", vTranslate.y), 5, 35, debugPaint);
            PointF center = getCenter();
            canvas.drawText("Source center: " + String.format("%.2f", center.x) + ":" + String.format("%.2f", center.y), 5, 55, debugPaint);

            if (anim != null) {
                PointF vCenterStart = sourceToViewCoord(anim.sCenterStart);
                PointF vCenterEndRequested = sourceToViewCoord(anim.sCenterEndRequested);
                PointF vCenterEnd = sourceToViewCoord(anim.sCenterEnd);
                canvas.drawCircle(vCenterStart.x, vCenterStart.y, 10, debugPaint);
                canvas.drawCircle(vCenterEndRequested.x, vCenterEndRequested.y, 20, debugPaint);
                canvas.drawCircle(vCenterEnd.x, vCenterEnd.y, 25, debugPaint);
                canvas.drawCircle(getWidth()/2, getHeight()/2, 30, debugPaint);
            }
        }
    }

    /**
     * Creates Paint objects once when first needed.
     */
    private void createPaints() {
        if (bitmapPaint == null) {
            bitmapPaint = new Paint();
            bitmapPaint.setAntiAlias(true);
            bitmapPaint.setFilterBitmap(true);
            bitmapPaint.setDither(true);
        }
        if (debugPaint == null && debug) {
            debugPaint = new Paint();
            debugPaint.setTextSize(18);
            debugPaint.setColor(Color.MAGENTA);
            debugPaint.setStyle(Style.STROKE);
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
            BitmapTileTask task = new BitmapTileTask(this, decoder, baseTile);
            task.execute();
        }

    }

    /**
     * Loads the optimum tiles for display at the current scale and translate, so the screen can be filled with tiles
     * that are at least as high resolution as the screen. Frees up bitmaps that are now off the screen.
     * @param load Whether to load the new tiles needed. Use false while scrolling/panning for performance.
     */
    private void refreshRequiredTiles(boolean load) {
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
                            BitmapTileTask task = new BitmapTileTask(this, decoder, tile);
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
     * Sets scale and translate ready for the next draw.
     */
    private void preDraw() {
        // If waiting to translate to new center position, set translate now
        if (sPendingCenter != null && pendingScale != null) {
            scale = pendingScale;
            vTranslate.x = (getWidth()/2) - (scale * sPendingCenter.x);
            vTranslate.y = (getHeight()/2) - (scale * sPendingCenter.y);
            sPendingCenter = null;
            pendingScale = null;
            fitToBounds(true);
            refreshRequiredTiles(true);
        }

        // On first display of base image set up position, and in other cases make sure scale is correct.
        fitToBounds(false);
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

        int reqWidth = (int)(sWidth() * adjustedScale);
        int reqHeight = (int)(sHeight() * adjustedScale);

        // Raw height and width of image
        int inSampleSize = 1;
        if (reqWidth == 0 || reqHeight == 0) {
            return 32;
        }

        if (sHeight() > reqHeight || sWidth() > reqWidth) {

            // Calculate ratios of height and width to requested height and width
            final int heightRatio = Math.round((float) sHeight() / (float) reqHeight);
            final int widthRatio = Math.round((float) sWidth() / (float) reqWidth);

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
     * Adjusts hypothetical future scale and translate values to keep scale within the allowed range and the image on screen. Minimum scale
     * is set so one dimension fills the view and the image is centered on the other dimension. Used to calculate what the target of an
     * animation should be.
     * @param center Whether the image should be centered in the dimension it's too small to fill. While animating this can be false to avoid changes in direction as bounds are reached.
     * @param sat The scale we want and the translation we're aiming for. The values are adjusted to be valid.
     */
    private void fitToBounds(boolean center, ScaleAndTranslate sat) {
        if (panLimit == PAN_LIMIT_OUTSIDE && isImageReady()) {
            center = false;
        }

        PointF vTranslate = sat.vTranslate;
        float scale = limitedScale(sat.scale);
        float scaleWidth = scale * sWidth();
        float scaleHeight = scale * sHeight();

        if (panLimit == PAN_LIMIT_CENTER && isImageReady()) {
            vTranslate.x = Math.max(vTranslate.x, getWidth()/2 - scaleWidth);
            vTranslate.y = Math.max(vTranslate.y, getHeight()/2 - scaleHeight);
        } else if (center) {
            vTranslate.x = Math.max(vTranslate.x, getWidth() - scaleWidth);
            vTranslate.y = Math.max(vTranslate.y, getHeight() - scaleHeight);
        } else {
            vTranslate.x = Math.max(vTranslate.x, -scaleWidth);
            vTranslate.y = Math.max(vTranslate.y, -scaleHeight);
        }

        // Asymmetric padding adjustments
        float xPaddingRatio = getPaddingLeft() > 0 || getPaddingRight() > 0 ? getPaddingLeft()/(float)(getPaddingLeft() + getPaddingRight()) : 0.5f;
        float yPaddingRatio = getPaddingTop() > 0 || getPaddingBottom() > 0 ? getPaddingTop()/(float)(getPaddingTop() + getPaddingBottom()) : 0.5f;

        float maxTx;
        float maxTy;
        if (panLimit == PAN_LIMIT_CENTER && isImageReady()) {
            maxTx = Math.max(0, getWidth()/2);
            maxTy = Math.max(0, getHeight()/2);
        } else if (center) {
            maxTx = Math.max(0, (getWidth() - scaleWidth) * xPaddingRatio);
            maxTy = Math.max(0, (getHeight() - scaleHeight) * yPaddingRatio);
        } else {
            maxTx = Math.max(0, getWidth());
            maxTy = Math.max(0, getHeight());
        }

        vTranslate.x = Math.min(vTranslate.x, maxTx);
        vTranslate.y = Math.min(vTranslate.y, maxTy);

        sat.scale = scale;
    }

    /**
     * Adjusts current scale and translate values to keep scale within the allowed range and the image on screen. Minimum scale
     * is set so one dimension fills the view and the image is centered on the other dimension.
     * @param center Whether the image should be centered in the dimension it's too small to fill. While animating this can be false to avoid changes in direction as bounds are reached.
     */
    private void fitToBounds(boolean center) {
        boolean init = false;
        if (vTranslate == null) {
            init = true;
            vTranslate = new PointF(0, 0);
        }
        if (satTemp == null) {
            satTemp = new ScaleAndTranslate(0, new PointF(0, 0));
        }
        satTemp.scale = scale;
        satTemp.vTranslate.set(vTranslate);
        fitToBounds(center, satTemp);
        scale = satTemp.scale;
        vTranslate.set(satTemp.vTranslate);
        if (init) {
            vTranslate.set(vTranslateForSCenter(sWidth()/2, sHeight()/2, scale));
        }
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
            int sTileWidth = sWidth()/xTiles;
            int sTileHeight = sHeight()/yTiles;
            int subTileWidth = sTileWidth/sampleSize;
            int subTileHeight = sTileHeight/sampleSize;
            while (subTileWidth > maxTileDimensions.x || (subTileWidth > getWidth() * 1.25 && sampleSize < fullImageSampleSize)) {
                xTiles += 1;
                sTileWidth = sWidth()/xTiles;
                subTileWidth = sTileWidth/sampleSize;
            }
            while (subTileHeight > maxTileDimensions.y || (subTileHeight > getHeight() * 1.25 && sampleSize < fullImageSampleSize)) {
                yTiles += 1;
                sTileHeight = sHeight()/yTiles;
                subTileHeight = sTileHeight/sampleSize;
            }
            List<Tile> tileGrid = new ArrayList<Tile>(xTiles * yTiles);
            for (int x = 0; x < xTiles; x++) {
                for (int y = 0; y < yTiles; y++) {
                    Tile tile = new Tile();
                    tile.sampleSize = sampleSize;
                    tile.visible = sampleSize == fullImageSampleSize;
                    tile.sRect = new Rect(
                            x * sTileWidth,
                            y * sTileHeight,
                            (x + 1) * sTileWidth,
                            (y + 1) * sTileHeight
                    );
                    tile.vRect = new Rect(0, 0, 0, 0);
                    tile.fileSRect = new Rect(tile.sRect);
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
     * Called by worker task when decoder is ready and image size and EXIF orientation is known.
     */
    private void onImageInited(ImageRegionDecoder decoder, int sWidth, int sHeight, int sOrientation) {
        this.decoder = decoder;
        setSourceSize(sWidth, sHeight);
        this.sourceOrientation = sOrientation;
        requestLayout();
        invalidate();

        // Inform subclasses that image dimensions are known and the scale and translate are set.
        if (!dimensionsReadySent) {
            preDraw();
            dimensionsReadySent = true;
            onImageReady();
            if (onImageEventListener != null) {
                onImageEventListener.onImageReady();
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
                if (onImageEventListener != null) {
                    onImageEventListener.onBaseLayerReady();
                }
            }
        }
    }

    /**
     * Async task used to get image details without blocking the UI thread.
     */
    private static class BitmapInitTask extends AsyncTask<Void, Void, int[]> {
        private final WeakReference<SubsamplingScaleImageView> viewRef;
        private final WeakReference<Context> contextRef;
        private final WeakReference<Class<? extends ImageRegionDecoder>> decoderClassRef;
        private final Uri source;
        private ImageRegionDecoder decoder;
        private Exception exception;

        public BitmapInitTask(SubsamplingScaleImageView view, Context context, Class<? extends ImageRegionDecoder> decoderClass, Uri source) {
            this.viewRef = new WeakReference<SubsamplingScaleImageView>(view);
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
                    int exifOrientation = ORIENTATION_0;
                    decoder = decoderClass.newInstance();
                    Point dimensions = decoder.init(context, source);
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
            final SubsamplingScaleImageView subsamplingScaleImageView = viewRef.get();
            if (subsamplingScaleImageView != null) {
                if (decoder != null && xyo != null && xyo.length == 3) {
                    subsamplingScaleImageView.onImageInited(decoder, xyo[0], xyo[1], xyo[2]);
                } else if (exception != null && subsamplingScaleImageView.onImageEventListener != null) {
                    subsamplingScaleImageView.onImageEventListener.onInitialisationError(exception);
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
                if (subsamplingScaleImageView != null && subsamplingScaleImageView.onImageEventListener != null) {
                    subsamplingScaleImageView.onImageEventListener.onTileLoadError(e);
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
                } else if (exception != null && subsamplingScaleImageView.onImageEventListener != null) {
                    subsamplingScaleImageView.onImageEventListener.onTileLoadError(exception);
                }
            }
        }
    }

    private static class Tile {

        private Rect sRect;
        private int sampleSize;
        private Bitmap bitmap;
        private boolean loading;
        private boolean visible;

        // Volatile fields instantiated once then updated before use to reduce GC.
        private Rect vRect;
        private Rect fileSRect;

    }

    private static class Anim {

        private float scaleStart; // Scale at start of anim
        private float scaleEnd; // Scale at end of anim (target)
        private PointF sCenterStart; // Source center point at start
        private PointF sCenterEnd; // Source center point at end, adjusted for pan limits
        private PointF sCenterEndRequested; // Source center point that was requested, without adjustment
        private PointF vFocusStart; // View point that was double tapped
        private PointF vFocusEnd; // Where the view focal point should be moved to during the anim
        private long duration = 500; // How long the anim takes
        private boolean interruptible = true; // Whether the anim can be interrupted by a touch
        private int easing = EASE_IN_OUT_QUAD; // Easing style
        private long time = System.currentTimeMillis(); // Start time

    }

    private static class ScaleAndTranslate {
        private ScaleAndTranslate(float scale, PointF vTranslate) {
            this.scale = scale;
            this.vTranslate = vTranslate;
        }
        private float scale;
        private PointF vTranslate;
    }

    /**
     * Set scale, center and orientation from saved state.
     */
    private void restoreState(ImageViewState state) {
        if (state != null && state.getCenter() != null) {
            this.orientation = state.getOrientation();
            this.pendingScale = state.getScale();
            this.sPendingCenter = state.getCenter();
            invalidate();
        }
    }

    /**
     * In SDK 14 and above, use canvas max bitmap width and height instead of the default 2048, to avoid redundant tiling.
     */
    private Point getMaxBitmapDimensions(Canvas canvas) {
        if (VERSION.SDK_INT >= 14) {
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
     * Get source width taking rotation into account.
     */
    @SuppressWarnings("SuspiciousNameCombination")
    private int sWidth() {
        int rotation = getRequiredRotation();
        if (rotation == 90 || rotation == 270) {
            return getSourceHeight();
        } else {
            return getSourceWidth();
        }
    }

    /**
     * Get source height taking rotation into account.
     */
    @SuppressWarnings("SuspiciousNameCombination")
    private int sHeight() {
        int rotation = getRequiredRotation();
        if (rotation == 90 || rotation == 270) {
            return getSourceWidth();
        } else {
            return getSourceHeight();
        }
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
     * Pythagoras distance between two points.
     */
    private float distance(float x0, float x1, float y0, float y1) {
        float x = x0 - x1;
        float y = y0 - y1;
        return FloatMath.sqrt(x * x + y * y);
    }

    /**
     * Releases all resources the view is using and resets the state, nulling any fields that use significant memory.
     * After you have called this method, the view can be re-used by setting a new image. Settings are remembered
     * but state (scale and center) is forgotten. You can restore these yourself if required.
     */
    public void recycle() {
        reset(true);
        bitmapPaint = null;
        debugPaint = null;
        tileBgPaint = null;
    }

    /**
     * Convert screen to source x coordinate.
     */
    private float viewToSourceX(float vx) {
        if (vTranslate == null) { return Float.NaN; }
        return (vx - vTranslate.x)/scale;
    }

    /**
     * Convert screen to source y coordinate.
     */
    private float viewToSourceY(float vy) {
        if (vTranslate == null) { return Float.NaN; }
        return (vy - vTranslate.y)/scale;
    }

    /**
     * Convert screen coordinate to source coordinate.
     */
    public final PointF viewToSourceCoord(PointF vxy) {
        return viewToSourceCoord(vxy.x, vxy.y, new PointF());
    }

    /**
     * Convert screen coordinate to source coordinate.
     */
    public final PointF viewToSourceCoord(float vx, float vy) {
        return viewToSourceCoord(vx, vy, new PointF());
    }

    /**
     * Convert screen coordinate to source coordinate.
     */
    public final PointF viewToSourceCoord(PointF vxy, PointF sTarget) {
        return viewToSourceCoord(vxy.x, vxy.y, sTarget);
    }

    /**
     * Convert screen coordinate to source coordinate.
     */
    public final PointF viewToSourceCoord(float vx, float vy, PointF sTarget) {
        if (vTranslate == null) {
            return null;
        }
        sTarget.set(viewToSourceX(vx), viewToSourceY(vy));
        return sTarget;
    }

    /**
     * Convert source to screen x coordinate.
     */
    private float sourceToViewX(float sx) {
        if (vTranslate == null) { return Float.NaN; }
        return (sx * scale) + vTranslate.x;
    }

    /**
     * Convert source to screen y coordinate.
     */
    private float sourceToViewY(float sy) {
        if (vTranslate == null) { return Float.NaN; }
        return (sy * scale) + vTranslate.y;
    }

    /**
     * Convert source coordinate to screen coordinate.
     */
    public final PointF sourceToViewCoord(PointF sxy) {
        return sourceToViewCoord(sxy.x, sxy.y, new PointF());
    }

    /**
     * Convert source coordinate to screen coordinate.
     */
    public final PointF sourceToViewCoord(float sx, float sy) {
        return sourceToViewCoord(sx, sy, new PointF());
    }

    /**
     * Convert source coordinate to screen coordinate.
     */
    public final PointF sourceToViewCoord(PointF sxy, PointF vTarget) {
        return sourceToViewCoord(sxy.x, sxy.y, vTarget);
    }

    /**
     * Convert source coordinate to screen coordinate.
     */
    public final PointF sourceToViewCoord(float sx, float sy, PointF vTarget) {
        if (vTranslate == null) {
            return null;
        }
        vTarget.set(sourceToViewX(sx), sourceToViewY(sy));
        return vTarget;
    }

    /**
     * Convert source rect to screen rect, integer values.
     */
    private Rect sourceToViewRect(Rect sRect, Rect vTarget) {
        vTarget.set(
            (int)sourceToViewX(sRect.left),
            (int)sourceToViewY(sRect.top),
            (int)sourceToViewX(sRect.right),
            (int)sourceToViewY(sRect.bottom)
        );
        return vTarget;
    }

    /**
     * Get the translation required to place a given source coordinate at the center of the screen, with the center
     * adjusted for asymmetric padding. Accepts the desired scale as an argument, so this is independent of current
     * translate and scale. The result is fitted to bounds, putting the image point as near to the screen center as permitted.
     */
    private PointF vTranslateForSCenter(float sCenterX, float sCenterY, float scale) {
        int vxCenter = getPaddingLeft() + (getWidth() - getPaddingRight() - getPaddingLeft())/2;
        int vyCenter = getPaddingTop() + (getHeight() - getPaddingBottom() - getPaddingTop())/2;
        if (satTemp == null) {
            satTemp = new ScaleAndTranslate(0, new PointF(0, 0));
        }
        satTemp.scale = scale;
        satTemp.vTranslate.set(vxCenter - (sCenterX * scale), vyCenter - (sCenterY * scale));
        fitToBounds(true, satTemp);
        return satTemp.vTranslate;
    }

    /**
     * Given a requested source center and scale, calculate what the actual center will have to be to keep the image in
     * pan limits, keeping the requested center as near to the middle of the screen as allowed.
     */
    private PointF limitedSCenter(float sCenterX, float sCenterY, float scale, PointF sTarget) {
        PointF vTranslate = vTranslateForSCenter(sCenterX, sCenterY, scale);
        int vxCenter = getPaddingLeft() + (getWidth() - getPaddingRight() - getPaddingLeft())/2;
        int vyCenter = getPaddingTop() + (getHeight() - getPaddingBottom() - getPaddingTop())/2;
        float sx = (vxCenter - vTranslate.x)/scale;
        float sy = (vyCenter - vTranslate.y)/scale;
        sTarget.set(sx, sy);
        return sTarget;
    }

    /**
     * Returns the minimum allowed scale.
     */
    private float minScale() {
        int vPadding = getPaddingBottom() + getPaddingTop();
        int hPadding = getPaddingLeft() + getPaddingRight();
        if (minimumScaleType == SCALE_TYPE_CENTER_CROP) {
            return Math.max((getWidth() - hPadding) / (float) sWidth(), (getHeight() - vPadding) / (float) sHeight());
        } else if (minimumScaleType == SCALE_TYPE_CUSTOM && minScale > 0) {
            return minScale;
        } else {
            return Math.min((getWidth() - hPadding) / (float) sWidth(), (getHeight() - vPadding) / (float) sHeight());
        }
    }

    /**
     * Adjust a requested scale to be within the allowed limits.
     */
    private float limitedScale(float targetScale) {
        targetScale = Math.max(minScale(), targetScale);
        targetScale = Math.min(maxScale, targetScale);
        return targetScale;
    }

    /**
     * Apply a selected type of easing.
     * @param type Easing type, from static fields
     * @param time Elapsed time
     * @param from Start value
     * @param change Target value
     * @param duration Anm duration
     * @return Current value
     */
    private float ease(int type, long time, float from, float change, long duration) {
        switch (type) {
            case EASE_IN_OUT_QUAD:
                return easeInOutQuad(time, from, change, duration);
            case EASE_OUT_QUAD:
                return easeOutQuad(time, from, change, duration);
            default:
                throw new IllegalStateException("Unexpected easing type: " + type);
        }
    }

    /**
     * Quadratic easing for fling. With thanks to Robert Penner - http://gizma.com/easing/
     * @param time Elapsed time
     * @param from Start value
     * @param change Target value
     * @param duration Anm duration
     * @return Current value
     */
    private float easeOutQuad(long time, float from, float change, long duration) {
        float progress = (float)time/(float)duration;
        return -change * progress*(progress-2) + from;
    }

    /**
     * Quadratic easing for scale and center animations. With thanks to Robert Penner - http://gizma.com/easing/
     * @param time Elapsed time
     * @param from Start value
     * @param change Target value
     * @param duration Anm duration
     * @return Current value
     */
    private float easeInOutQuad(long time, float from, float change, long duration) {
        float timeF = time/(duration/2f);
        if (timeF < 1) {
            return (change/2f * timeF * timeF) + from;
        } else {
            timeF--;
            return (-change/2f) * (timeF * (timeF - 2) - 1) + from;
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
     * Set the pan limiting style. See static fields. Normally {@link #PAN_LIMIT_INSIDE} is best, for image galleries.
     */
    public final void setPanLimit(int panLimit) {
        if (!VALID_PAN_LIMITS.contains(panLimit)) {
            throw new IllegalArgumentException("Invalid pan limit: " + panLimit);
        }
        this.panLimit = panLimit;
        if (isImageReady()) {
            fitToBounds(true);
            invalidate();
        }
    }

    /**
     * Set the minimum scale type. See static fields. Normally {@link #SCALE_TYPE_CENTER_INSIDE} is best, for image galleries.
     */
    public final void setMinimumScaleType(int scaleType) {
        if (!VALID_SCALE_TYPES.contains(scaleType)) {
            throw new IllegalArgumentException("Invalid scale type: " + scaleType);
        }
        this.minimumScaleType = scaleType;
        if (isImageReady()) {
            fitToBounds(true);
            invalidate();
        }
    }

    /**
     * Set the maximum scale allowed. A value of 1 means 1:1 pixels at maximum scale. You may wish to set this according
     * to screen density - on a retina screen, 1:1 may still be too small. Consider using {@link #setMinimumDpi(int)},
     * which is density aware.
     */
    public final void setMaxScale(float maxScale) {
        this.maxScale = maxScale;
    }

    /**
     * Set the minimum scale allowed. A value of 1 means 1:1 pixels at minimum scale. You may wish to set this according
     * to screen density. Consider using {@link #setMaximumDpi(int)}, which is density aware.
     */
    public final void setMinScale(float minScale) {
        this.minScale = minScale;
    }

    /**
     * This is a screen density aware alternative to {@link #setMaxScale(float)}; it allows you to express the maximum
     * allowed scale in terms of the minimum pixel density. This avoids the problem of 1:1 scale still being
     * too small on a high density screen. A sensible starting point is 160 - the default used by this view.
     * @param dpi Source image pixel density at maximum zoom.
     */
    public final void setMinimumDpi(int dpi) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float averageDpi = (metrics.xdpi + metrics.ydpi)/2;
        setMaxScale(averageDpi/dpi);
    }

    /**
     * This is a screen density aware alternative to {@link #setMinScale(float)}; it allows you to express the minimum
     * allowed scale in terms of the maximum pixel density.
     * @param dpi Source image pixel density at minimum zoom.
     */
    public final void setMaximumDpi(int dpi) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float averageDpi = (metrics.xdpi + metrics.ydpi)/2;
        setMinScale(averageDpi/dpi);
    }

    /**
     * Returns the maximum allowed scale.
     */
    public float getMaxScale() {
        return maxScale;
    }

    /**
     * Returns the minimum allowed scale.
     */
    public final float getMinScale() {
        return minScale();
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
     * Returns the source point at the center of the view.
     */
    public final PointF getCenter() {
        int mX = getWidth()/2;
        int mY = getHeight()/2;
        return viewToSourceCoord(mX, mY);
    }

    /**
     * Returns the current scale value.
     */
    public final float getScale() {
        return scale;
    }

    /**
     * Externally change the scale and translation of the source image. This may be used with getCenter() and getScale()
     * to restore the scale and zoom after a screen rotate.
     * @param scale New scale to set.
     * @param sCenter New source image coordinate to center on the screen, subject to boundaries.
     */
    public final void setScaleAndCenter(float scale, PointF sCenter) {
        this.anim = null;
        this.pendingScale = scale;
        this.sPendingCenter = sCenter;
        this.sRequestedCenter = sCenter;
        invalidate();
    }

    /**
     * Fully zoom out and return the image to the middle of the screen. This might be useful if you have a view pager
     * and want images to be reset when the user has moved to another page.
     */
    public final void resetScaleAndCenter() {
        this.anim = null;
        this.pendingScale = limitedScale(0);
        if (isImageReady()) {
            this.sPendingCenter = new PointF(sWidth()/2, sHeight()/2);
        } else {
            this.sPendingCenter = new PointF(0, 0);
        }
        invalidate();
    }

    /**
     * Subclasses can override this method to be informed when the view is set up and ready for rendering,
     * so they can skip their own rendering until the base layer dimensions are known and the scale and
     * translate have been calculated. This is called before the base layer tiles have been loaded;
     * to be notified when they are ready override {@link #onBaseLayerReady()}. You can also use an
     * {@link OnImageEventListener} to receive notification of these events.
     */
    protected void onImageReady() {

    }

    /**
     * Call to find whether the view is initialised and ready for rendering tiles. The view is ready
     * once the dimensions of the image are known.
     */
    public final boolean isImageReady() {
        return dimensionsReadySent && vTranslate != null && tileMap != null && getSourceWidth() > 0 && getSourceHeight() > 0;
    }

    /**
     * Subclasses can override this method to be informed when the base layer tiles have been loaded -
     * this is called immediately before the view draws them. You can also use an {@link OnImageEventListener}
     * to receive notification of this event.
     */
    protected void onBaseLayerReady() {

    }

    /**
     * Call to find whether the base layer tiles have been loaded. Before this event the view is blank.
     */
    public final boolean isBaseLayerReady() {
        return baseLayerReadySent;
    }

    /**
     * Get the current state of the view (scale, center, orientation) for restoration after rotate. Will return null if
     * the view is not ready.
     */
    public final ImageViewState getState() {
        if (vTranslate != null && getSourceWidth() > 0 && getSourceHeight() > 0) {
            return new ImageViewState(getScale(), getCenter(), orientation);
        }
        return null;
    }

    /**
     * Returns true if zoom gesture detection is enabled.
     */
    public final boolean isZoomEnabled() {
        return zoomEnabled;
    }

    /**
     * Enable or disable zoom gesture detection. Disabling zoom locks the the current scale.
     */
    public final void setZoomEnabled(boolean zoomEnabled) {
        this.zoomEnabled = zoomEnabled;
    }

    /**
     * Returns true if pan gesture detection is enabled.
     */
    public final boolean isPanEnabled() {
        return panEnabled;
    }

    /**
     * Enable or disable pan gesture detection. Disabling pan causes the image to be centered.
     */
    public final void setPanEnabled(boolean panEnabled) {
        this.panEnabled = panEnabled;
        if (!panEnabled && vTranslate != null) {
            vTranslate.x = (getWidth()/2) - (scale * (sWidth()/2));
            vTranslate.y = (getHeight()/2) - (scale * (sHeight()/2));
            if (isImageReady()) {
                refreshRequiredTiles(true);
                invalidate();
            }
        }
    }

    /**
     * Returns true if double tap & swipe to zoom is enabled.
     */
    public final boolean isQuickScaleEnabled() {
        return quickScaleEnabled;
    }

    /**
     * Enable or disable double tap & swipe to zoom.
     */
    public final void setQuickScaleEnabled(boolean quickScaleEnabled) {
        this.quickScaleEnabled = zoomEnabled;
    }

    /**
     * Set a solid color to render behind tiles, useful for displaying transparent PNGs.
     * @param tileBgColor Background color for tiles.
     */
    public final void setTileBackgroundColor(int tileBgColor) {
        if (Color.alpha(tileBgColor) == 0) {
            tileBgPaint = null;
        } else {
            tileBgPaint = new Paint();
            tileBgPaint.setStyle(Style.FILL);
            tileBgPaint.setColor(tileBgColor);
        }
        invalidate();
    }

    /**
     * Set the scale the image will zoom in to when double tapped. This also the scale point where a double tap is interpreted
     * as a zoom out gesture - if the scale is greater than 90% of this value, a double tap zooms out. Avoid using values
     * greater than the max zoom.
     * @param doubleTapZoomScale New value for double tap gesture zoom scale.
     */
    public final void setDoubleTapZoomScale(float doubleTapZoomScale) {
        this.doubleTapZoomScale = doubleTapZoomScale;
    }

    /**
     * A density aware alternative to {@link #setDoubleTapZoomScale(float)}; this allows you to express the scale the
     * image will zoom in to when double tapped in terms of the image pixel density. Values lower than the max scale will
     * be ignored. A sensible starting point is 160 - the default used by this view.
     * @param dpi New value for double tap gesture zoom scale.
     */
    public final void setDoubleTapZoomDpi(int dpi) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float averageDpi = (metrics.xdpi + metrics.ydpi)/2;
        setDoubleTapZoomScale(averageDpi/dpi);
    }

    /**
     * Set the type of zoom animation to be used for double taps. See static fields.
     * @param doubleTapZoomStyle New value for zoom style.
     */
    public final void setDoubleTapZoomStyle(int doubleTapZoomStyle) {
        if (!VALID_ZOOM_STYLES.contains(doubleTapZoomStyle)) {
            throw new IllegalArgumentException("Invalid zoom style: " + doubleTapZoomStyle);
        }
        this.doubleTapZoomStyle = doubleTapZoomStyle;
    }

    /**
     * Enables visual debugging, showing tile boundaries and sizes.
     */
    public final void setDebug(boolean debug) {
        this.debug = debug;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setOnLongClickListener(OnLongClickListener onLongClickListener) {
        this.onLongClickListener = onLongClickListener;
    }

    /**
     * Add a listener allowing notification of load and error events.
     */
    public void setOnImageEventListener(OnImageEventListener onImageEventListener) {
        this.onImageEventListener = onImageEventListener;
    }

    /**
     * Creates a panning animation builder, that when started will animate the image to place the given coordinates of
     * the image in the center of the screen. If doing this would move the image beyond the edges of the screen, the
     * image is instead animated to move the center point as near to the center of the screen as is allowed - it's
     * guaranteed to be on screen.
     * @param sCenter Target center point
     * @return {@link AnimationBuilder} instance. Call {@link SubsamplingScaleImageView.AnimationBuilder#start()} to start the anim.
     */
    public AnimationBuilder animateCenter(PointF sCenter) {
        if (!isImageReady()) {
            return null;
        }
        return new AnimationBuilder(sCenter);
    }

    /**
     * Creates a scale animation builder, that when started will animate a zoom in or out. If this would move the image
     * beyond the panning limits, the image is automatically panned during the animation.
     * @param scale Target scale.
     * @return {@link AnimationBuilder} instance. Call {@link SubsamplingScaleImageView.AnimationBuilder#start()} to start the anim.
     */
    public AnimationBuilder animateScale(float scale) {
        if (!isImageReady()) {
            return null;
        }
        return new AnimationBuilder(scale);
    }

    /**
     * Creates a scale animation builder, that when started will animate a zoom in or out. If this would move the image
     * beyond the panning limits, the image is automatically panned during the animation.
     * @param scale Target scale.
     * @return {@link AnimationBuilder} instance. Call {@link SubsamplingScaleImageView.AnimationBuilder#start()} to start the anim.
     */
    public AnimationBuilder animateScaleAndCenter(float scale, PointF sCenter) {
        if (!isImageReady()) {
            return null;
        }
        return new AnimationBuilder(scale, sCenter);
    }

    /**
     * Builder class used to set additional options for a scale animation. Create an instance using {@link #animateScale(float)},
     * then set your options and call {@link #start()}.
     */
    public final class AnimationBuilder {

        private final float targetScale;
        private final PointF targetSCenter;
        private final PointF vFocus;
        private long duration = 500;
        private int easing = EASE_IN_OUT_QUAD;
        private boolean interruptible = true;
        private boolean panLimited = true;

        private AnimationBuilder(PointF sCenter) {
            this.targetScale = scale;
            this.targetSCenter = sCenter;
            this.vFocus = null;
        }

        private AnimationBuilder(float scale) {
            this.targetScale = scale;
            this.targetSCenter = getCenter();
            this.vFocus = null;
        }

        private AnimationBuilder(float scale, PointF sCenter) {
            this.targetScale = scale;
            this.targetSCenter = sCenter;
            this.vFocus = null;
        }

        private AnimationBuilder(float scale, PointF sCenter, PointF vFocus) {
            this.targetScale = scale;
            this.targetSCenter = sCenter;
            this.vFocus = vFocus;
        }

        /**
         * Desired duration of the anim in milliseconds. Default is 500.
         * @param duration duration in milliseconds.
         * @return this builder for method chaining.
         */
        public AnimationBuilder withDuration(long duration) {
            this.duration = duration;
            return this;
        }

        /**
         * Whether the animation can be interrupted with a touch. Default is true.
         * @param interruptible interruptible flag.
         * @return this builder for method chaining.
         */
        public AnimationBuilder withInterruptible(boolean interruptible) {
            this.interruptible = interruptible;
            return this;
        }

        /**
         * Set the easing style. See static fields. {@link #EASE_IN_OUT_QUAD} is recommended, and the default.
         * @param easing easing style.
         * @return this builder for method chaining.
         */
        public AnimationBuilder withEasing(int easing) {
            if (!VALID_EASING_STYLES.contains(easing)) {
                throw new IllegalArgumentException("Unknown easing type: " + easing);
            }
            this.easing = easing;
            return this;
        }

        /**
         * Only for internal use. When set to true, the animation proceeds towards the actual end point - the nearest
         * point to the center allowed by pan limits. When false, animation is in the direction of the requested end
         * point and is stopped when the limit for each axis is reached. The latter behaviour is used for flings but
         * nothing else.
         */
        private AnimationBuilder withPanLimited(boolean panLimited) {
            this.panLimited = panLimited;
            return this;
        }

        /**
         * Starts the animation.
         */
        public void start() {
            int vxCenter = getPaddingLeft() + (getWidth() - getPaddingRight() - getPaddingLeft())/2;
            int vyCenter = getPaddingTop() + (getHeight() - getPaddingBottom() - getPaddingTop())/2;
            float targetScale = limitedScale(this.targetScale);
            PointF targetSCenter = panLimited ? limitedSCenter(this.targetSCenter.x, this.targetSCenter.y, targetScale, new PointF()) : this.targetSCenter;
            anim = new Anim();
            anim.scaleStart = scale;
            anim.scaleEnd = targetScale;
            anim.time = System.currentTimeMillis();
            anim.sCenterEndRequested = targetSCenter;
            anim.sCenterStart = getCenter();
            anim.sCenterEnd = targetSCenter;
            anim.vFocusStart = sourceToViewCoord(targetSCenter);
            anim.vFocusEnd = new PointF(
                vxCenter,
                vyCenter
            );
            anim.duration = duration;
            anim.interruptible = interruptible;
            anim.easing = easing;
            anim.time = System.currentTimeMillis();

            if (vFocus != null) {
                // Calculate where translation will be at the end of the anim
                float vTranslateXEnd = vFocus.x - (targetScale * anim.sCenterStart.x);
                float vTranslateYEnd = vFocus.y - (targetScale * anim.sCenterStart.y);
                ScaleAndTranslate satEnd = new ScaleAndTranslate(targetScale, new PointF(vTranslateXEnd, vTranslateYEnd));
                // Fit the end translation into bounds
                fitToBounds(true, satEnd);
                // Adjust the position of the focus point at end so image will be in bounds
                anim.vFocusEnd = new PointF(
                        vFocus.x + (satEnd.vTranslate.x - vTranslateXEnd),
                        vFocus.y + (satEnd.vTranslate.y - vTranslateYEnd)
                );
            }

            invalidate();
        }

    }

    /**
     * An event listener, allowing subclasses and activities to be notified of significant events.
     */
    public static interface OnImageEventListener {

        /**
         * Called when the dimensions of the image are known. This occurs when the bitmap region decoder
         * has initialised, but before the base layer tiles have been decoded. The view will be briefly
         * blank but scale and translate will be calculated and ready for use to draw overlays.
         */
        void onImageReady();

        /**
         * Called when the lowest resolution base layer of tiles are loaded and about to be rendered,
         * in other words the view will no longer be blank. You can use this event as a trigger to
         * display overlays, remove loading animations etc.
         */
        void onBaseLayerReady();

        /**
         * Called when the dimensions of an image file could not be determined. This method cannot be
         * relied upon; certain encoding types of supported image formats can result in corrupt or
         * blank images being loaded and displayed with no detectable error.
         * @param e The exception thrown. This error is also logged by the view.
         */
        void onInitialisationError(Exception e);

        /**
         * Called when an image tile could not be loaded. This method cannot be relied upon; certain
         * encoding types of supported image formats can result in corrupt or blank images being loaded
         * and displayed with no detectable error. Most cases where an unsupported file is used will
         * result in an error caught by {@link #onInitialisationError(Exception)}.
         * @param e The exception thrown. This error is logged by the view.
         */
        void onTileLoadError(Exception e);

    }

    /**
     * Default implementation of {@link OnImageEventListener} for extension. This does nothing in any method.
     */
    public class DefaultOnImageEventListener implements OnImageEventListener {

        @Override public void onImageReady() { }
        @Override public void onBaseLayerReady() { }
        @Override public void onInitialisationError(Exception e) { }
        @Override public void onTileLoadError(Exception e) { }

    }

}
