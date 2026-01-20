package su.grinev.myvpn.traffic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Custom view for rendering traffic rate graph with gradients.
 * Supports horizontal scrolling and pinch-to-zoom.
 */
public class TrafficGraphView extends View {
    private static final int GRID_LINES = 5;
    private static final int INCOMING_COLOR = 0xFF4CAF50; // Green
    private static final int INCOMING_COLOR_TRANSPARENT = 0x004CAF50;
    private static final int OUTGOING_COLOR = 0xFFFFCA28; // Yellow/Amber
    private static final int OUTGOING_COLOR_TRANSPARENT = 0x00FFCA28;
    private static final int GRID_COLOR = 0x33FFFFFF;
    private static final int TEXT_COLOR = 0xAAFFFFFF;
    private static final int TIME_TEXT_COLOR = 0x88FFFFFF;

    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 5.0f;
    private static final int MIN_VISIBLE_POINTS = 20;
    private static final int DEFAULT_VISIBLE_POINTS = 60;

    private final Paint incomingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint incomingFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outgoingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outgoingFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path incomingPath = new Path();
    private final Path incomingFillPath = new Path();
    private final Path outgoingPath = new Path();
    private final Path outgoingFillPath = new Path();

    private final List<TrafficStats> dataPoints = new ArrayList<>();
    private double maxRate = 10.0; // Minimum 10 Mbps scale

    // Scroll and zoom state
    private float scrollOffset = 0f; // Offset in data points (can be fractional)
    private float scale = 1.0f; // Zoom scale
    private int visiblePoints = DEFAULT_VISIBLE_POINTS;

    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleGestureDetector;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public TrafficGraphView(Context context) {
        super(context);
        gestureDetector = new GestureDetector(context, new GestureListener());
        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleListener());
        init();
    }

    public TrafficGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        gestureDetector = new GestureDetector(context, new GestureListener());
        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleListener());
        init();
    }

    public TrafficGraphView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        gestureDetector = new GestureDetector(context, new GestureListener());
        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleListener());
        init();
    }

    private void init() {
        // Incoming line paint (green)
        incomingPaint.setStyle(Paint.Style.STROKE);
        incomingPaint.setStrokeWidth(3f);
        incomingPaint.setColor(INCOMING_COLOR);

        // Incoming fill paint (green gradient)
        incomingFillPaint.setStyle(Paint.Style.FILL);

        // Outgoing line paint (yellow)
        outgoingPaint.setStyle(Paint.Style.STROKE);
        outgoingPaint.setStrokeWidth(3f);
        outgoingPaint.setColor(OUTGOING_COLOR);

        // Outgoing fill paint (yellow gradient)
        outgoingFillPaint.setStyle(Paint.Style.FILL);

        // Grid paint
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setColor(GRID_COLOR);

        // Rate text paint (Y axis)
        textPaint.setColor(TEXT_COLOR);
        textPaint.setTextSize(24f);

        // Time text paint (X axis)
        timeTextPaint.setColor(TIME_TEXT_COLOR);
        timeTextPaint.setTextSize(20f);
        timeTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void updateData(List<TrafficStats> history) {
        dataPoints.clear();
        dataPoints.addAll(history);

        // Calculate max rate from all data
        maxRate = 10.0;
        for (TrafficStats stats : dataPoints) {
            maxRate = Math.max(maxRate, stats.getIncomingRateMbps());
            maxRate = Math.max(maxRate, stats.getOutgoingRateMbps());
        }

        // Round up maxRate to nice number
        maxRate = Math.ceil(maxRate / 10) * 10;
        if (maxRate < 10) maxRate = 10;

        // Auto-scroll to end if at the end
        if (scrollOffset >= getMaxScrollOffset() - 1) {
            scrollOffset = getMaxScrollOffset();
        }

        invalidate();
    }

    private float getMaxScrollOffset() {
        return Math.max(0, dataPoints.size() - visiblePoints);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = scaleGestureDetector.onTouchEvent(event);
        handled = gestureDetector.onTouchEvent(event) || handled;
        return handled || super.onTouchEvent(event);
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (e1 == null) return false;

            int graphWidth = getWidth() - 120; // Account for padding
            float pointWidth = graphWidth / (float) visiblePoints;

            // Convert pixel distance to data points
            float scrollDelta = distanceX / pointWidth;
            scrollOffset += scrollDelta;

            // Clamp scroll offset
            scrollOffset = Math.max(0, Math.min(scrollOffset, getMaxScrollOffset()));

            invalidate();
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            // Could add momentum scrolling here if desired
            return false;
        }
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            scale *= scaleFactor;
            scale = Math.max(MIN_SCALE, Math.min(scale, MAX_SCALE));

            // Update visible points based on scale
            visiblePoints = (int) (DEFAULT_VISIBLE_POINTS / scale);
            visiblePoints = Math.max(MIN_VISIBLE_POINTS, visiblePoints);

            // Adjust scroll to keep focus point
            scrollOffset = Math.max(0, Math.min(scrollOffset, getMaxScrollOffset()));

            invalidate();
            return true;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateGradients(h);
    }

    private void updateGradients(int height) {
        // Incoming gradient (green, top to bottom fade)
        LinearGradient incomingGradient = new LinearGradient(
                0, 0, 0, height,
                INCOMING_COLOR, INCOMING_COLOR_TRANSPARENT,
                Shader.TileMode.CLAMP
        );
        incomingFillPaint.setShader(incomingGradient);

        // Outgoing gradient (yellow, top to bottom fade)
        LinearGradient outgoingGradient = new LinearGradient(
                0, 0, 0, height,
                OUTGOING_COLOR, OUTGOING_COLOR_TRANSPARENT,
                Shader.TileMode.CLAMP
        );
        outgoingFillPaint.setShader(outgoingGradient);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        int paddingLeft = 60;
        int paddingRight = 60;
        int paddingTop = 40;
        int paddingBottom = 50;
        int graphWidth = width - paddingLeft - paddingRight;
        int graphHeight = height - paddingTop - paddingBottom;

        // Draw grid and labels
        drawGrid(canvas, paddingLeft, paddingTop, graphWidth, graphHeight);

        // Draw time axis
        drawTimeAxis(canvas, paddingLeft, paddingTop, graphWidth, graphHeight);

        // Clip to graph area
        canvas.save();
        canvas.clipRect(paddingLeft, paddingTop, paddingLeft + graphWidth, paddingTop + graphHeight);

        // Draw graphs
        if (!dataPoints.isEmpty()) {
            drawGraph(canvas, true, incomingPath, incomingFillPath,
                    incomingPaint, incomingFillPaint, paddingLeft, paddingTop, graphWidth, graphHeight);
            drawGraph(canvas, false, outgoingPath, outgoingFillPath,
                    outgoingPaint, outgoingFillPaint, paddingLeft, paddingTop, graphWidth, graphHeight);
        }

        canvas.restore();
    }

    private void drawGrid(Canvas canvas, int paddingLeft, int paddingTop, int graphWidth, int graphHeight) {
        // Horizontal grid lines
        for (int i = 0; i <= GRID_LINES; i++) {
            float y = paddingTop + (graphHeight * i / (float) GRID_LINES);
            canvas.drawLine(paddingLeft, y, paddingLeft + graphWidth, y, gridPaint);

            // Draw rate label
            double rate = maxRate * (GRID_LINES - i) / GRID_LINES;
            String label = formatRate(rate);
            canvas.drawText(label, 5, y + 8, textPaint);
        }
    }

    private void drawTimeAxis(Canvas canvas, int paddingLeft, int paddingTop, int graphWidth, int graphHeight) {
        if (dataPoints.isEmpty()) return;

        int timeMarkers = 5;

        for (int i = 0; i <= timeMarkers; i++) {
            float x = paddingLeft + (graphWidth * i / (float) timeMarkers);

            // Draw vertical grid line
            canvas.drawLine(x, paddingTop, x, paddingTop + graphHeight, gridPaint);

            // Calculate which data point this corresponds to
            int dataIndex = (int) (scrollOffset + (visiblePoints * i / (float) timeMarkers));
            if (dataIndex >= 0 && dataIndex < dataPoints.size()) {
                TrafficStats stats = dataPoints.get(dataIndex);
                String timeLabel = timeFormat.format(new Date(stats.getTimestamp()));
                canvas.drawText(timeLabel, x, paddingTop + graphHeight + 35, timeTextPaint);
            }
        }
    }

    private void drawGraph(Canvas canvas, boolean isIncoming, Path linePath, Path fillPath,
                           Paint linePaint, Paint fillPaint, int paddingLeft, int paddingTop,
                           int graphWidth, int graphHeight) {
        if (dataPoints.isEmpty()) return;

        linePath.reset();
        fillPath.reset();

        float pointWidth = graphWidth / (float) visiblePoints;

        int startIndex = (int) scrollOffset;
        int endIndex = Math.min(dataPoints.size(), startIndex + visiblePoints + 2);

        if (startIndex >= dataPoints.size()) return;

        // Fractional offset for smooth scrolling
        float fractionalOffset = scrollOffset - startIndex;

        boolean first = true;
        float firstX = 0, lastX = 0;

        for (int i = startIndex; i < endIndex; i++) {
            TrafficStats stats = dataPoints.get(i);
            double rate = isIncoming ? stats.getIncomingRateMbps() : stats.getOutgoingRateMbps();

            float x = paddingLeft + (i - startIndex - fractionalOffset) * pointWidth;
            float y = paddingTop + graphHeight - (float) (rate / maxRate * graphHeight);

            // Clamp y to graph bounds
            y = Math.max(paddingTop, Math.min(paddingTop + graphHeight, y));

            if (first) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, paddingTop + graphHeight);
                fillPath.lineTo(x, y);
                firstX = x;
                first = false;
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
            lastX = x;
        }

        // Close fill path
        if (!first) {
            fillPath.lineTo(lastX, paddingTop + graphHeight);
            fillPath.lineTo(firstX, paddingTop + graphHeight);
            fillPath.close();
        }

        // Draw fill first, then line on top
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);
    }

    private String formatRate(double rate) {
        if (rate >= 1000) {
            return String.format(Locale.US, "%.1fG", rate / 1000);
        } else if (rate >= 100) {
            return String.format(Locale.US, "%.0fM", rate);
        } else {
            return String.format(Locale.US, "%.1fM", rate);
        }
    }

    /**
     * Scroll to the end of the data (most recent).
     */
    public void scrollToEnd() {
        scrollOffset = getMaxScrollOffset();
        invalidate();
    }

    /**
     * Scroll to the beginning of the data (oldest).
     */
    public void scrollToStart() {
        scrollOffset = 0;
        invalidate();
    }

    /**
     * Reset zoom to default.
     */
    public void resetZoom() {
        scale = 1.0f;
        visiblePoints = DEFAULT_VISIBLE_POINTS;
        scrollOffset = Math.max(0, Math.min(scrollOffset, getMaxScrollOffset()));
        invalidate();
    }
}
