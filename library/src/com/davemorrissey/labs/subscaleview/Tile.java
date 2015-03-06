package com.davemorrissey.labs.subscaleview;

import android.graphics.Bitmap;
import android.graphics.Rect;

public class Tile {

    public final Rect sRect = new Rect();
    public final int sampleSize;

    public Bitmap bitmap;
    public boolean loading;
    public boolean visible;

    // Volatile fields instantiated once then updated before use to reduce GC.
    public final Rect vRect = new Rect();
    public final Rect fileSRect = new Rect();

    public Tile(final int x, final int y, final int tileWidth, final int tileHeight, int sampleSize) {
        sRect.set(x, y, x + tileWidth, y + tileHeight);
        fileSRect.set(sRect);
        this.sampleSize = sampleSize;
    }
}
