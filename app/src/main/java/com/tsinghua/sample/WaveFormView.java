package com.tsinghua.sample;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

public class WaveFormView extends View {

    private Paint paint;
    private float[] waveformData; // Store waveform data

    public WaveFormView(Context context) {
        super(context);
        init();
    }

    public WaveFormView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WaveFormView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    // Initialize the Paint object
    private void init() {
        paint = new Paint();
        paint.setColor(0xFF00FF00); // Green color for waveform
        paint.setStrokeWidth(2);
        paint.setStyle(Paint.Style.STROKE);
        paint.setAntiAlias(true);
    }

    // Update the waveform data and trigger a redraw
    public void updateWaveform(float[] waveform) {
        this.waveformData = waveform;
        invalidate(); // Request to redraw the view
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (waveformData == null || waveformData.length == 0) {
            return;
        }

        // Start drawing the waveform
        Path path = new Path();
        int width = getWidth();
        int height = getHeight();

        // Start at the middle of the view
        path.moveTo(0, height / 2);

        // Scale the waveform to fit the view size
        float xIncrement = (float) width / waveformData.length;
        for (int i = 0; i < waveformData.length; i++) {
            float y = (waveformData[i] * height) / 2 + height / 2; // Scale and center the waveform
            path.lineTo(i * xIncrement, y);
        }

        // Draw the waveform path
        canvas.drawPath(path, paint);
    }
}
