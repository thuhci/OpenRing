package com.tsinghua.openring;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class PlotView extends View {

    private final List<Integer> dataBuffer = new ArrayList<>();
    private final int bufferSize = 512;  // 默认最大数据点数
    private final Paint axisPaint = new Paint();
    private final Paint plotPaint = new Paint();
    private float axisPadding = 10f;  // 调整坐标轴的填充量

    private int axisColor = Color.parseColor("#ABD0B1"); // 坐标轴颜色
    private int plotColor = Color.parseColor("#00FF00"); // 曲线颜色

    private int maxY = Integer.MIN_VALUE;
    private int minY = Integer.MAX_VALUE;

    // 增加一个阈值参数来检测数据变化的大小
    private final int MIN_Y_RANGE = 10;

    public PlotView(Context context) {
        super(context);
        init();
    }

    public PlotView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        axisPaint.setColor(axisColor);
        axisPaint.setStrokeWidth(2);
        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setTextSize(30);

        plotPaint.setColor(plotColor);
        plotPaint.setStrokeWidth(3);
        plotPaint.setStyle(Paint.Style.STROKE);
    }

    // 自定义坐标轴和绘制曲线的颜色
    public void setAxisColor(int color) {
        axisColor = color;
        axisPaint.setColor(axisColor);
        invalidate();  // 重新绘制
    }

    public void setPlotColor(int color) {
        plotColor = color;
        plotPaint.setColor(plotColor);
        invalidate();  // 重新绘制
    }

    public void addValue(int value) {
        if (dataBuffer.size() >= bufferSize) {
            dataBuffer.remove(0);
        }

        dataBuffer.add(value);

        updateYRange();

        adjustYScale();

        postInvalidate();
    }

    public void clearPlot() {
        dataBuffer.clear();
        maxY = Integer.MIN_VALUE;
        minY = Integer.MAX_VALUE;
        postInvalidate(); // 请求重新绘制
    }

    // 动态更新 Y 轴的最大最小值
    private void updateYRange() {
        if (dataBuffer.isEmpty()) return;

        int localMaxY = Integer.MIN_VALUE;
        int localMinY = Integer.MAX_VALUE;

        // 只计算当前缓冲区内的数据
        for (int value : dataBuffer) {
            localMaxY = Math.max(localMaxY, value);
            localMinY = Math.min(localMinY, value);
        }

        maxY = localMaxY;
        minY = localMinY;
    }

    // 动态调整 Y 轴的范围，若数据变化较小，适当扩大Y轴的范围
    private void adjustYScale() {
        if ((maxY - minY) < MIN_Y_RANGE) {
            int range = MIN_Y_RANGE - (maxY - minY);
            maxY += range;
            minY -= range;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();
        float axisWidth = width - 2 * axisPadding;
        float axisHeight = height - 2 * axisPadding;

        // 绘制坐标轴
        canvas.drawRect(axisPadding, axisPadding, width - axisPadding, height - axisPadding, axisPaint);

        // 如果没有数据，直接返回
        if (dataBuffer.isEmpty()) return;

        // 动态调整 Y 轴比例
        float yScale = axisHeight / (maxY - minY);

        // 创建绘制路径
        Path path = new Path();

        // 绘制曲线
        for (int i = 0; i < dataBuffer.size(); i++) {
            int value = dataBuffer.get(i);
            float x = axisPadding + i * (axisWidth / (bufferSize - 1));
            float y = height - axisPadding - (value - minY) * yScale;  // 根据最大最小值调整 Y 轴
            if (i == 0) {
                path.moveTo(x, y);  // 起始点
            } else {
                path.lineTo(x, y);  // 连接其他点
            }
        }

        // 绘制曲线
        canvas.drawPath(path, plotPaint);
    }
}
