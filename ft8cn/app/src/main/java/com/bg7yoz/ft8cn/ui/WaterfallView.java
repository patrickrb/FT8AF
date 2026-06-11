package com.bg7yoz.ft8cn.ui;
/**
 * Waterfall view custom control.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import static android.graphics.Bitmap.Config.ARGB_8888;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.timer.UtcTimer;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

public class WaterfallView extends View {
    private static final String TAG = "WaterfallView";
    private static final float FT8_SIGNAL_BANDWIDTH_HZ = 50f;

    private int blockHeight = 2;//Color block height
    private float freq_width = 1;//Frequency width
    private final int cycle = 2;
    private final int symbols = 93;
    private int spectrumWidth = 3500;//Spectrum display width in Hz
    private Bitmap lastBitMap = null;
    private Canvas _canvas;
    private final Paint linePaint = new Paint();
    private Paint touchPaint = new Paint();
    private final Paint fontPaint = new Paint();
    private final Paint messagePaint = new Paint();
    private final Paint textLinePaint = new Paint();
//    private final Paint messagePaintBack = new Paint();//Message background
    private final Paint utcPaint = new Paint();
    Paint linearPaint = new Paint();
    private final Paint utcPainBack = new Paint();
    private float pathStart = 0;
    private float pathEnd = 0;

    private int touch_x = -1;
    private int freq_hz = -1;
    private boolean drawMessage = false;//Whether to draw message content

    // Track the bitmap dimensions to avoid recreating on minor layout changes
    private int bitmapWidth = 0;
    private int bitmapHeight = 0;

    // TX frequency marker overlay
    private float txFrequency = -1f;
    private boolean txActive = false;
    private final Paint txMarkerPaint = new Paint();

    // FT8 period timestamp tracking
    private long lastTimestampPeriod = -1;
    // Gates the decoded-label stamp to once per decode slot (the current mode's slot
    // length, not a fixed 15s), so a slot's labels are painted exactly once even though the
    // decode-done flag re-arms on every decode pass.
    private final WaterfallLabelGate messageGate = new WaterfallLabelGate();
    private final Paint timestampLinePaint = new Paint();

    public WaterfallView(Context context) {
        super(context);
    }

    public WaterfallView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public WaterfallView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    ArrayList<Ft8Message> messages= new ArrayList<>();


    /**
     * Convert dp value to pixels
     *
     * @param dp dp value
     * @return pixels
     */
    private int dpToPixel(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp
                , getResources().getDisplayMetrics());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w <= 0 || h <= 0) return;

        // Skip bitmap recreation if the size hasn't meaningfully changed.
        // Compose layout can cause small oscillations in the view size;
        // recreating the bitmap each time wipes all accumulated waterfall data.
        if (lastBitMap != null && w == bitmapWidth && Math.abs(h - bitmapHeight) < 20) {
            return;
        }

        Log.d(TAG, String.format("onSizeChanged: w=%d h=%d oldw=%d oldh=%d", w, h, oldw, oldh));
        setClickable(true);
        bitmapWidth = w;
        bitmapHeight = h;
        blockHeight = h / (symbols * cycle);
        if (blockHeight < 1) blockHeight = 1;
        freq_width = (float) w / spectrumWidth;
        Log.d(TAG, String.format("Bitmap created: %dx%d, blockHeight=%d, freq_width=%.2f, spectrumWidth=%d",
                w, h, blockHeight, freq_width, spectrumWidth));
        lastBitMap = Bitmap.createBitmap(w, h, ARGB_8888);
        // Fresh bitmap wiped the stamped labels, so allow the current cycle to re-stamp.
        messageGate.reset();
        _canvas = new Canvas(lastBitMap);
        Paint blackPaint = new Paint();
        blackPaint.setColor(0xFF000000);
        _canvas.drawRect(0, 0, w, h, blackPaint);//Paint background black first to prevent text overlap

        //linePaint = new Paint();
        linePaint.setColor(0xff990000);
        touchPaint = new Paint();
        touchPaint.setColor(0xff00ffff);
        touchPaint.setStrokeWidth(getResources().getDisplayMetrics().density);


        //fontPaint = new Paint();
        fontPaint.setTextSize(dpToPixel(10));
        fontPaint.setColor(0xff00ffff);
        fontPaint.setAntiAlias(true);
        fontPaint.setDither(true);
        fontPaint.setTextAlign(Paint.Align.LEFT);


        textLinePaint.setColor(0xff00ffff);
        textLinePaint.setAntiAlias(true);
        textLinePaint.setDither(true);
        textLinePaint.setStrokeWidth(2);
        textLinePaint.setStyle(Paint.Style.FILL_AND_STROKE);


        // messagePaint = new Paint();
        messagePaint.setTextSize(dpToPixel(11));
        messagePaint.setColor(0xff00ffff);
        messagePaint.setAntiAlias(true);
        messagePaint.setDither(true);
        messagePaint.setStrokeWidth(0);
        messagePaint.setStyle(Paint.Style.FILL_AND_STROKE);
        messagePaint.setTextAlign(Paint.Align.CENTER);
        messagePaint.setShadowLayer(10,5,5,Color.BLACK);

        //utcPaint = new Paint();
        utcPaint.setTextSize(dpToPixel(10));
        utcPaint.setColor(0xff00ffff);//
        utcPaint.setAntiAlias(true);
        utcPaint.setDither(true);
        utcPaint.setStrokeWidth(0);
        utcPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        utcPaint.setTextAlign(Paint.Align.LEFT);


        //utcPainBack = new Paint();
        utcPainBack.setTextSize(dpToPixel(10));
        utcPainBack.setColor(0xff000000);//Opaque background
        utcPainBack.setAntiAlias(true);
        utcPainBack.setDither(true);
        utcPainBack.setStrokeWidth(dpToPixel(4));
        utcPainBack.setStyle(Paint.Style.FILL_AND_STROKE);
        utcPainBack.setTextAlign(Paint.Align.LEFT);


        pathStart = blockHeight * 2;
        pathEnd = blockHeight * 90;
        if (pathEnd < 130 * getResources().getDisplayMetrics().density) {//Ensure there's enough space to write text
            pathEnd = 130 * getResources().getDisplayMetrics().density;
        }

        txMarkerPaint.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
        txMarkerPaint.setStyle(Paint.Style.STROKE);

        timestampLinePaint.setColor(0x6000ffff);
        timestampLinePaint.setStrokeWidth(1);
        timestampLinePaint.setStyle(Paint.Style.STROKE);

        super.onSizeChanged(w, h, oldw, oldh);

    }

    @SuppressLint("DefaultLocale")
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (lastBitMap == null) return;
        canvas.drawBitmap(lastBitMap, 0, 0, null);

        // Draw TX frequency marker lines
        if (txFrequency > 0 && freq_width > 0) {
            txMarkerPaint.setColor(0xFFEF4444);
            float halfBw = FT8_SIGNAL_BANDWIDTH_HZ / 2f;
            float x1 = (txFrequency - halfBw) * freq_width;
            float x2 = (txFrequency + halfBw) * freq_width;
            canvas.drawLine(x1, 0, x1, getHeight(), txMarkerPaint);
            canvas.drawLine(x2, 0, x2, getHeight(), txMarkerPaint);
        }

        //Calculate frequency
        if (touch_x > 0) {//Draw touch line
            freq_hz = Math.round((float) spectrumWidth * (float) touch_x / (float) getWidth());
            if (freq_hz > spectrumWidth - 100) {
                freq_hz = spectrumWidth - 100;
            }
            if (freq_hz < 100) {
                freq_hz = 100;
            }

            if (touch_x > getWidth() / 2) {
                fontPaint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(String.format("%dHz", freq_hz)
                        , touch_x - 10, 250, fontPaint);
            } else {
                fontPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(String.format("%dHz", freq_hz)
                        , touch_x + 10, 250, fontPaint);
            }
            canvas.drawLine(touch_x, 0, touch_x, getHeight(), touchPaint);

        }
        // Do NOT call invalidate() here. The view is invalidated externally
        // when new data arrives via setWaveData(). Calling invalidate() from
        // onDraw() creates a continuous redraw loop that triggers layout
        // thrashing in Compose's AndroidView, causing onSizeChanged to fire
        // repeatedly and wipe all accumulated waterfall data.
    }

    public void setWaveData(int[] data, List<Ft8Message> msgs) {
        if (drawMessage&& msgs!=null){//Copy messages to draw to prevent multi-thread access conflicts
            messages=new ArrayList<>(msgs);
        }else {
            messages.clear();//When message marking is disabled, clear existing messages
        }

        if (data == null) {
            Log.w(TAG, "setWaveData: data is null, skipping");
            return;
        }
        if (data.length <= 0) {
            Log.w(TAG, "setWaveData: data is empty, skipping");
            return;
        }
        if (lastBitMap == null) {
            Log.w(TAG, "setWaveData: bitmap not initialized, skipping");
            return;
        }

        // Use bitmap dimensions for drawing, not getWidth()/getHeight() which
        // may differ from the bitmap size during Compose layout transitions.
        int drawWidth = bitmapWidth;
        int drawHeight = bitmapHeight;

        int[] colors = new int[data.length];

        //Color block distribution
        for (int i = 0; i < data.length; i++) {


            if (data[i] < 128) {//Below half volume, use blue 0~256
                colors[i] = 0xff000000 | (data[i] << 1);
            } else if (data[i] < 192) {
                colors[i] = 0xff0000ff | (((data[i] - 127)) << 10);//Amplify 4x
//                colors[i] = 0xff000000 | (data[i] * 2 * 256 + 255);
            } else {
                colors[i] = 0xff00ffff | (((data[i] - 127)) << 18);//Amplify 4x
            }
        }
        // Scale gradient so the visible portion (0..drawWidth) maps to 0..spectrumWidth Hz.
        // The FFT data covers 0 to Nyquist (sampleRate/2). We want spectrumWidth Hz
        // to fill the view, so the full gradient length = drawWidth * (Nyquist / spectrumWidth).
        float nyquist = GeneralVariables.audioSampleRate / 2f;
        float gradientScale = nyquist / spectrumWidth;
        LinearGradient linearGradient = new LinearGradient(0, 0, drawWidth * gradientScale, 0, colors
                , null, Shader.TileMode.CLAMP);
        linearPaint.setShader(linearGradient);
        Bitmap bitmap = Bitmap.createBitmap(lastBitMap, 0, 0, drawWidth, drawHeight - blockHeight);
        _canvas.drawBitmap(bitmap, 0, blockHeight, null);
        bitmap.recycle();
        _canvas.drawRect(0, 0, drawWidth, blockHeight, linearPaint);

        // Draw FT8 period timestamp at 15-second boundaries
        long utcMs = UtcTimer.getSystemTime();
        long period = (utcMs / 1000) / 15;
        if (period != lastTimestampPeriod) {
            lastTimestampPeriod = period;
            // Draw horizontal line at the boundary between new row and scrolled content
            _canvas.drawLine(0, blockHeight, drawWidth, blockHeight, timestampLinePaint);
            // Format UTC time label
            long utcSec = utcMs / 1000;
            long h = (utcSec / 3600) % 24;
            long m = (utcSec % 3600) / 60;
            long s = utcSec % 60;
            @SuppressLint("DefaultLocale")
            String timeLabel = String.format("%02d:%02d:%02d", h, m, s);
            float textX = dpToPixel(2);
            float textY = blockHeight + utcPaint.getTextSize() + dpToPixel(1);
            // Draw outline then fill for readability over any spectrum color
            _canvas.drawText(timeLabel, textX, textY, utcPainBack);
            _canvas.drawText(timeLabel, textX, textY, utcPaint);
            Log.d(TAG, String.format("Timestamp drawn: %s (period=%d, utcMs=%d, blockHeight=%d, textY=%.1f, drawWidth=%d)",
                    timeLabel, period, utcMs, blockHeight, textY, drawWidth));
        }

        //Messages have 3 types: normal, CQ, and involving me
        if (drawMessage && messages != null) {
            drawMessage = false;//Consume the one-shot arming
            // FT8 decodes in more than one pass per cycle (a normal pass and a slower deep
            // pass), and every completed pass re-arms drawMessage. Without this guard the
            // same labels get stamped onto the scrolling bitmap several times per cycle, at
            // different scroll offsets, so they appear to repeat down the waterfall —
            // including over the next cycle's not-yet-populated rows where the signal is
            // gone. Stamp at most once per decode slot. Key the gate on the current mode's
            // slot length (NOT a fixed 15s) so FT4 (7.5s) and FT2 (3.8s) each get one stamp
            // per slot instead of one stamp per two-or-more slots. Pass slotMillis as part
            // of the key too: this view (and its gate) is reused across mode changes, and a
            // new mode's slotPeriod can collide with one already stamped under the old mode,
            // which would wrongly suppress the first label after the switch.
            long slotMillis = GeneralVariables.currentMode().slotMillis;
            long slotPeriod = slotMillis > 0 ? utcMs / slotMillis : period;
            if (messageGate.shouldStamp(slotMillis, slotPeriod)) {
                Log.d(TAG, String.format("Drawing %d messages on waterfall", messages.size()));
                for (Ft8Message msg : messages) {

                if (msg.inMyCall()) {//Related to me
                    messagePaint.setColor(0xffffb2b2);
                    textLinePaint.setColor(0xffffb2b2);
                } else if (msg.checkIsCQ()) {//CQ
                    messagePaint.setColor(0xffeeee00);
                    textLinePaint.setColor(0xffeeee00);
                } else {
                    messagePaint.setColor(0xff00ffff);
                    textLinePaint.setColor(0xff00ffff);
                }

                Path path = new Path();

                path.moveTo(msg.freq_hz * freq_width, pathStart);
                path.lineTo(msg.freq_hz * freq_width, pathEnd);



//                _canvas.drawTextOnPath(msg.getMessageText(true), path
//                        , 0, 0, messagePaintBack);//Message background
                _canvas.drawTextOnPath(msg.getMessageText(true), path
                        , 0, 0, messagePaint);//Message
                if (GeneralVariables.checkQSLCallsign(msg.getCallsignFrom())) {//Draw strikethrough line
                    float text_len = messagePaint.measureText(msg.getMessageText(true));
                    float text_start = ((pathEnd- pathStart)-text_len)/2;
                    float text_high =dpToPixel(4);//messagePaint.getFontSpacing()/2;
                    _canvas.drawLine(msg.freq_hz * freq_width + text_high , text_start
                            , msg.freq_hz * freq_width + text_high, text_len + text_start, textLinePaint);
                }
                }
            }
        }


    }

    public void setTouch_x(int touch_x) {
        this.touch_x = touch_x;
    }

    public void setDrawMessage(boolean drawMessage) {
        this.drawMessage = drawMessage;
    }

    public int getFreq_hz() {
        return freq_hz;
    }

    public void setTxFrequency(float freq) {
        Log.d(TAG, String.format("setTxFrequency: %.1f Hz", freq));
        this.txFrequency = freq;
    }

    public void setTxActive(boolean active) {
        Log.d(TAG, String.format("setTxActive: %b", active));
        this.txActive = active;
    }

    public void setSpectrumWidth(int width) {
        Log.d(TAG, String.format("setSpectrumWidth: %d Hz (was %d)", width, spectrumWidth));
        this.spectrumWidth = width;
        if (bitmapWidth > 0) {
            freq_width = (float) bitmapWidth / spectrumWidth;
        }
    }
}
