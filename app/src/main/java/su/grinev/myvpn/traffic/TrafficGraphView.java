package su.grinev.myvpn.traffic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom view for rendering traffic rate graph with gradients.
 */
public class TrafficGraphView extends View {
    private static final int GRID_LINES = 5;
    private static final int INCOMING_COLOR = 0xFF4CAF50; // Green
    private static final int INCOMING_COLOR_TRANSPARENT = 0x004CAF50;
    private static final int OUTGOING_COLOR = 0xFFFFCA28; // Yellow/Amber
    private static final int OUTGOING_COLOR_TRANSPARENT = 0x00FFCA28;
    private static final int GRID_COLOR = 0x33FFFFFF;
    private static final int TEXT_COLOR = 0xAAFFFFFF;

    private final Paint incomingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint incomingFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outgoingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outgoingFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path incomingPath = new Path();
    private final Path incomingFillPath = new Path();
    private final Path outgoingPath = new Path();
    private final Path outgoingFillPath = new Path();

    private final List<Double> incomingRates = new ArrayList<>();
    private final List<Double> outgoingRates = new ArrayList<>();
    private double maxRate = 10.0; // Minimum 10 Mbps scale

    public TrafficGraphView(Context context) {
        super(context);
        init();
    }

    public TrafficGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TrafficGraphView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Incoming line paint (green)
        incomingPaint.setStyle(Paint.Style.STROKE);
        incomingPaint.setStrokeWidth(4f);
        incomingPaint.setColor(INCOMING_COLOR);

        // Incoming fill paint (green gradient)
        incomingFillPaint.setStyle(Paint.Style.FILL);

        // Outgoing line paint (yellow)
        outgoingPaint.setStyle(Paint.Style.STROKE);
        outgoingPaint.setStrokeWidth(4f);
        outgoingPaint.setColor(OUTGOING_COLOR);

        // Outgoing fill paint (yellow gradient)
        outgoingFillPaint.setStyle(Paint.Style.FILL);

        // Grid paint
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setColor(GRID_COLOR);

        // Text paint
        textPaint.setColor(TEXT_COLOR);
        textPaint.setTextSize(28f);
    }

    public void updateData(List<TrafficStats> history) {
        incomingRates.clear();
        outgoingRates.clear();

        maxRate = 10.0; // Reset to minimum

        for (TrafficStats stats : history) {
            double inRate = stats.getIncomingRateMbps();
            double outRate = stats.getOutgoingRateMbps();

            incomingRates.add(inRate);
            outgoingRates.add(outRate);

            maxRate = Math.max(maxRate, inRate);
            maxRate = Math.max(maxRate, outRate);
        }

        // Round up maxRate to nice number
        maxRate = Math.ceil(maxRate / 10) * 10;
        if (maxRate < 10) maxRate = 10;

        invalidate();
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
        int padding = 60;
        int graphWidth = width - padding * 2;
        int graphHeight = height - padding * 2;

        // Draw grid
        drawGrid(canvas, padding, graphWidth, graphHeight);

        // Draw graphs
        if (!incomingRates.isEmpty()) {
            drawGraph(canvas, incomingRates, incomingPath, incomingFillPath,
                    incomingPaint, incomingFillPaint, padding, graphWidth, graphHeight);
        }

        if (!outgoingRates.isEmpty()) {
            drawGraph(canvas, outgoingRates, outgoingPath, outgoingFillPath,
                    outgoingPaint, outgoingFillPaint, padding, graphWidth, graphHeight);
        }
    }

    private void drawGrid(Canvas canvas, int padding, int graphWidth, int graphHeight) {
        // Horizontal grid lines
        for (int i = 0; i <= GRID_LINES; i++) {
            float y = padding + (graphHeight * i / (float) GRID_LINES);
            canvas.drawLine(padding, y, padding + graphWidth, y, gridPaint);

            // Draw rate label
            double rate = maxRate * (GRID_LINES - i) / GRID_LINES;
            String label = formatRate(rate);
            canvas.drawText(label, 5, y + 10, textPaint);
        }

        // Vertical grid lines (time markers)
        int timeMarkers = 6;
        for (int i = 0; i <= timeMarkers; i++) {
            float x = padding + (graphWidth * i / (float) timeMarkers);
            canvas.drawLine(x, padding, x, padding + graphHeight, gridPaint);
        }
    }

    private void drawGraph(Canvas canvas, List<Double> rates, Path linePath, Path fillPath,
                           Paint linePaint, Paint fillPaint, int padding, int graphWidth, int graphHeight) {
        if (rates.isEmpty()) return;

        linePath.reset();
        fillPath.reset();

        int maxPoints = 60;
        int dataSize = rates.size();
        float pointSpacing = graphWidth / (float) (maxPoints - 1);

        // Start fill path at bottom left
        int startIndex = Math.max(0, dataSize - maxPoints);
        float startX = padding + (maxPoints - (dataSize - startIndex)) * pointSpacing;

        fillPath.moveTo(startX, padding + graphHeight);

        boolean first = true;
        for (int i = startIndex; i < dataSize; i++) {
            int pointIndex = i - startIndex + (maxPoints - (dataSize - startIndex));
            float x = padding + pointIndex * pointSpacing;
            float y = padding + graphHeight - (float) (rates.get(i) / maxRate * graphHeight);

            // Clamp y to graph bounds
            y = Math.max(padding, Math.min(padding + graphHeight, y));

            if (first) {
                linePath.moveTo(x, y);
                fillPath.lineTo(x, y);
                first = false;
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }

        // Close fill path
        if (!first) {
            float endX = padding + graphWidth;
            fillPath.lineTo(endX, padding + graphHeight);
            fillPath.close();
        }

        // Draw fill first, then line on top
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);
    }

    private String formatRate(double rate) {
        if (rate >= 1000) {
            return String.format("%.1f Gbps", rate / 1000);
        } else {
            return String.format("%.0f Mbps", rate);
        }
    }
}
