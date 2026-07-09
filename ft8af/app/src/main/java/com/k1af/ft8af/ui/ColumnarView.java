package com.k1af.ft8af.ui;

import static android.graphics.Bitmap.Config.ARGB_8888;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import com.k1af.ft8af.GeneralVariables;

import java.util.ArrayList;
import java.util.List;

/**
 * Columnar spectrum view.
 * @author BGY70Z
 * @date 2023-03-20
 */
public class ColumnarView extends View {
    private static final String TAG = "ColumnarView";
    private static final float FT8_SIGNAL_BANDWIDTH_HZ = 50f;

    // Width of each bar
    private int width;
    // Spacing between each bar
    private final int spacing = 1;
    // Block height
    private final int blockHeight = 5;
    // Block drop speed
    private int blockSpeed = 5;
    // Distance between block and bar
    private final int distance = 2;

    private boolean drawblock = false;
    private final Paint paint = new Paint();
    private final List<Rect> newData = new ArrayList<>();
    private final List<Rect> blockData = new ArrayList<>();
    // Peak-hold state, one top per drawn bar. Sized to newData every frame by
    // PeakBlocks.update so a bin-count change can never index past the bars.
    private int[] blockTops = new int[0];

    private int spectrumWidth = 3500;//Spectrum display width in Hz

    private Bitmap lastBitMap=null;
    private Canvas _canvas;
    private Paint linePaint;
    private int touch_x = -1;
    private Paint touchPaint;
    private int freq_hz=-1;

    // TX frequency marker overlay
    private float txFrequency = -1f;
    private boolean txActive = false;
    private final Paint txMarkerPaint = new Paint();

    public void setBlockSpeed(int blockSpeed) {
        this.blockSpeed = blockSpeed;
    }

    public ColumnarView(Context context) {
        super(context);

    }

    public ColumnarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ColumnarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setShowBlock(boolean showBlock) {
        drawblock = showBlock;
    }

    public void setWaveData(int[] data) {
        if (data == null) {
            return;
        }
        if (data.length <= 0) {
            return;
        }
        // Calculate how many FFT bins correspond to spectrumWidth Hz.
        // The full data array covers 0 to Nyquist (sampleRate/2).
        float nyquist = GeneralVariables.audioSampleRate / 2f;
        int binsToShow = Math.min(data.length, Math.round((float) spectrumWidth / nyquist * data.length));
        if (binsToShow <= 0) binsToShow = data.length / 2;

        width = getWidth() / binsToShow;
        if (drawblock) {// Whether to show the peak block
            // newData still holds the PREVIOUS frame's bars here (it is rebuilt
            // below). Drive the peak-hold state off those bars, sized to them,
            // so a bin-count change between frames can't index out of bounds.
            int bars = newData.size();
            if (bars > 0) {
                int floorTop = getHeight() - blockHeight;
                int[] barTops = new int[bars];
                for (int i = 0; i < bars; i++) {
                    barTops[i] = newData.get(i).top;
                }
                blockTops = PeakBlocks.update(blockTops, barTops, floorTop,
                        blockHeight, blockSpeed, distance);
                if (blockData.size() != bars) {
                    blockData.clear();
                    for (int i = 0; i < bars; i++) {
                        blockData.add(new Rect());
                    }
                }
                for (int i = 0; i < bars; i++) {
                    Rect block = blockData.get(i);
                    block.left = newData.get(i).left;
                    block.right = newData.get(i).right;
                    block.top = blockTops[i];
                    block.bottom = blockTops[i] + blockHeight;
                }
            }
        }
        newData.clear();
        float rateHeight =  0.95f * getHeight() / 256;// 0.95 is the ratio; max bar height does not exceed 95%
        for (int i = 0; i < binsToShow; i++) {
            Rect colRect = new Rect();
            if (newData.size() == 0) {
                colRect.left = 0;
            } else {
                colRect.left = i * getWidth() / binsToShow;
            }
            int val = BinAggregation.combine(data, i, GeneralVariables.getSpectrumBinAggregation());
            colRect.top = getHeight() - Math.round(val * rateHeight);
            // Guarantee at least a 1px-wide bar. With a wide spectrum span the
            // bin count can exceed the pixel width (e.g. 560 bins across ~1080px
            // gives width=1), so width - spacing collapses to 0 and every bar
            // draws as a zero-width rect — the whole strip renders black.
            colRect.right = colRect.left + Math.max(1, width - spacing);
            colRect.bottom = getHeight();
            newData.add(colRect);
        }
    }


    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        setClickable(true);
        super.onSizeChanged(w, h, oldw, oldh);

        lastBitMap= Bitmap.createBitmap(w,h, ARGB_8888);
        _canvas=new Canvas(lastBitMap);
        LinearGradient linearGradient=new LinearGradient(0f, 0f, 0f, getHeight(),
                new int[]{0xff00ffff,0xff00ffff, Color.BLUE}
                , new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
        paint.setShader(linearGradient);
        linePaint = new Paint();
        linePaint.setColor(0xff990000);
        touchPaint = new Paint();
        touchPaint.setColor(0xff00ffff);
        touchPaint.setStrokeWidth(2);

        txMarkerPaint.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
        txMarkerPaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        _canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        for (int i = 0; i < newData.size(); i++) {
            _canvas.drawRect(newData.get(i), paint);
        }
        if (drawblock) {
            for (int i = 0; i < blockData.size(); i++) {
                _canvas.drawRect(blockData.get(i), paint);
            }
        }
        canvas.drawBitmap(lastBitMap,0,0,null);
        if (touch_x>0) {
            // Calculate frequency
            freq_hz = Math.round((float) spectrumWidth * (float) touch_x / (float) getWidth());
            canvas.drawLine(touch_x, 0, touch_x, getHeight(), touchPaint);
        }

        // Draw TX frequency marker lines
        if (txFrequency > 0 && getWidth() > 0) {
            txMarkerPaint.setColor(0xFFEF4444);
            float freqWidth = (float) getWidth() / spectrumWidth;
            float halfBw = FT8_SIGNAL_BANDWIDTH_HZ / 2f;
            float x1 = (txFrequency - halfBw) * freqWidth;
            float x2 = (txFrequency + halfBw) * freqWidth;
            canvas.drawLine(x1, 0, x1, getHeight(), txMarkerPaint);
            canvas.drawLine(x2, 0, x2, getHeight(), txMarkerPaint);
        }

        // Do NOT call invalidate() here — the view is invalidated externally
        // when new data arrives. Self-invalidation causes layout thrashing
        // in Compose's AndroidView.
    }
    public void setTouch_x(int touch_x) {
        this.touch_x = touch_x;
    }

    public int getFreq_hz() {
        return freq_hz;
    }

    public void setTxFrequency(float freq) {
        this.txFrequency = freq;
    }

    public void setTxActive(boolean active) {
        this.txActive = active;
    }

    public void setSpectrumWidth(int width) {
        this.spectrumWidth = width;
    }
}
