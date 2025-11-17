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
    private final List<Integer> dataBuffer2 = new ArrayList<>();  // 用于双线绘制
    private final int bufferSize = 512;  // 默认最大数据点数
    private final Paint axisPaint = new Paint();
    private final Paint plotPaint = new Paint();
    private final Paint plotPaint2 = new Paint();  // 第二条线的画笔
    private float axisPadding = 35f;  // 增加填充量为标签留出空间

    private int axisColor = Color.parseColor("#ABD0B1"); // 坐标轴颜色
    private int plotColor = Color.parseColor("#00FF00"); // 曲线颜色
    private int plotColor2 = Color.parseColor("#FFA500"); // 第二条曲线颜色

    private int maxY = Integer.MIN_VALUE;
    private int minY = Integer.MAX_VALUE;

    // 增加一个阈值参数来检测数据变化的大小
    private final int MIN_Y_RANGE = 10;

    // 是否使用双线模式
    private boolean dualLineMode = false;

    // 是否显示数据点标签（默认不显示，用于实时测量）
    private boolean showDataLabels = false;

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

        plotPaint2.setColor(plotColor2);
        plotPaint2.setStrokeWidth(3);
        plotPaint2.setStyle(Paint.Style.STROKE);
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
        dataBuffer2.clear();
        maxY = Integer.MIN_VALUE;
        minY = Integer.MAX_VALUE;
        dualLineMode = false;
        postInvalidate(); // 请求重新绘制
    }

    // 批量设置数据（用于历史数据展示）
    public void setData(List<Integer> data) {
        dataBuffer.clear();
        dataBuffer2.clear();
        dualLineMode = false;
        if (data != null && !data.isEmpty()) {
            dataBuffer.addAll(data);
            updateYRange();
            adjustYScale();
        } else {
            maxY = Integer.MIN_VALUE;
            minY = Integer.MAX_VALUE;
        }
        postInvalidate();
    }

    // 设置双线数据（用于BP的SYS和DIA）
    public void setDualData(List<Integer> data1, List<Integer> data2) {
        dataBuffer.clear();
        dataBuffer2.clear();
        dualLineMode = true;
        if (data1 != null && !data1.isEmpty()) {
            dataBuffer.addAll(data1);
        }
        if (data2 != null && !data2.isEmpty()) {
            dataBuffer2.addAll(data2);
        }
        updateYRangeForDual();
        adjustYScale();
        postInvalidate();
    }

    // 设置第二条线的颜色
    public void setPlotColor2(int color) {
        plotColor2 = color;
        plotPaint2.setColor(plotColor2);
        invalidate();
    }

    // 设置是否显示数据点标签（用于历史数据显示）
    public void setShowDataLabels(boolean show) {
        this.showDataLabels = show;
        invalidate();
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

    // 双线模式下更新 Y 轴范围
    private void updateYRangeForDual() {
        if (dataBuffer.isEmpty() && dataBuffer2.isEmpty()) return;

        int localMaxY = Integer.MIN_VALUE;
        int localMinY = Integer.MAX_VALUE;

        for (int value : dataBuffer) {
            localMaxY = Math.max(localMaxY, value);
            localMinY = Math.min(localMinY, value);
        }

        for (int value : dataBuffer2) {
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
        float labelPadding = 60f;  // 为Y轴标签留出空间
        float axisWidth = width - axisPadding - labelPadding;
        float axisHeight = height - 2 * axisPadding;

        // 不绘制边框，避免数据波形超出框时看起来诡异

        // 如果没有数据，显示提示信息
        if (dataBuffer.isEmpty()) {
            Paint textPaint = new Paint();
            textPaint.setColor(Color.GRAY);
            textPaint.setTextSize(30);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("No data", width / 2, height / 2, textPaint);
            return;
        }

        // 动态调整 Y 轴比例
        float yScale = axisHeight / (maxY - minY);

        // 绘制Y轴刻度标签
        Paint labelPaint = new Paint();
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(24);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        labelPaint.setAntiAlias(true);

        // 最大值（稍微向下移动一点，留出空间给可能的数值标签）
        canvas.drawText(String.valueOf(maxY), axisPadding + labelPadding - 10, axisPadding + 8, labelPaint);
        // 中间值
        int midValue = (maxY + minY) / 2;
        canvas.drawText(String.valueOf(midValue), axisPadding + labelPadding - 10, height / 2 + 8, labelPaint);
        // 最小值
        canvas.drawText(String.valueOf(minY), axisPadding + labelPadding - 10, height - axisPadding + 5, labelPaint);

        // 如果只有一个数据点，绘制一个圆点
        if (dataBuffer.size() == 1) {
            Paint pointPaint = new Paint();
            pointPaint.setColor(plotColor);
            pointPaint.setStyle(Paint.Style.FILL);
            float x = axisPadding + labelPadding + axisWidth / 2;
            int value = dataBuffer.get(0);
            float y = height - axisPadding - (value - minY) * yScale;
            canvas.drawCircle(x, y, 8, pointPaint);

            // 如果启用了标签显示，绘制数值标签
            if (showDataLabels) {
                Paint valuePaint = new Paint();
                valuePaint.setColor(plotColor);
                valuePaint.setTextSize(28);
                valuePaint.setTextAlign(Paint.Align.CENTER);
                valuePaint.setAntiAlias(true);

                // 调整标签位置，确保不超出边界
                float labelY = y - 15;
                if (labelY < axisPadding + 5) {  // 标签会超出顶部
                    labelY = y + 35;  // 移到点下方
                } else if (y + 35 > height - axisPadding) {  // 如果下方也会超出
                    labelY = axisPadding + 20;  // 固定在靠近顶部的位置
                }
                canvas.drawText(String.valueOf(value), x, labelY, valuePaint);
            }
            return;
        }

        // 确定数据点数量（用于计算 X 坐标）
        int dataSize = dataBuffer.size();
        int effectiveSize = Math.max(dataSize, 2);  // 至少2个点，避免除零

        // 如果启用了标签显示，准备数值标签画笔
        Paint valuePaint = null;
        if (showDataLabels) {
            valuePaint = new Paint();
            valuePaint.setColor(plotColor);
            valuePaint.setTextSize(22);
            valuePaint.setTextAlign(Paint.Align.CENTER);
            valuePaint.setAntiAlias(true);
        }

        // 绘制第一条曲线和数据点
        Path path = new Path();
        for (int i = 0; i < dataBuffer.size(); i++) {
            int value = dataBuffer.get(i);
            float x = axisPadding + labelPadding + i * (axisWidth / (effectiveSize - 1));
            float y = height - axisPadding - (value - minY) * yScale;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }

            // 如果启用了标签显示，绘制数据点圆圈和标签
            if (showDataLabels) {
                Paint pointPaint = new Paint();
                pointPaint.setColor(plotColor);
                pointPaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(x, y, 5, pointPaint);

                // 调整标签位置，确保不超出边界
                float labelY = y - 10;
                if (labelY < axisPadding + 5) {  // 标签会超出顶部
                    labelY = y + 20;  // 移到点下方
                }
                if (labelY > height - axisPadding) {  // 标签会超出底部
                    labelY = height - axisPadding - 5;  // 固定在底部边界内
                }
                canvas.drawText(String.valueOf(value), x, labelY, valuePaint);
            }
        }
        canvas.drawPath(path, plotPaint);

        // 如果是双线模式，绘制第二条曲线
        if (dualLineMode && !dataBuffer2.isEmpty()) {
            // 如果启用了标签显示，准备第二条线的数值标签画笔
            Paint valuePaint2 = null;
            if (showDataLabels) {
                valuePaint2 = new Paint();
                valuePaint2.setColor(plotColor2);
                valuePaint2.setTextSize(22);
                valuePaint2.setTextAlign(Paint.Align.CENTER);
                valuePaint2.setAntiAlias(true);
            }

            if (dataBuffer2.size() == 1) {
                // 绘制单点
                Paint pointPaint = new Paint();
                pointPaint.setColor(plotColor2);
                pointPaint.setStyle(Paint.Style.FILL);
                float x = axisPadding + labelPadding + axisWidth / 2;
                int value = dataBuffer2.get(0);
                float y = height - axisPadding - (value - minY) * yScale;
                canvas.drawCircle(x, y, 5, pointPaint);

                // 如果启用了标签显示，绘制数值标签（在点下方，避免与第一条线重叠）
                if (showDataLabels) {
                    // 调整标签位置，确保不超出边界
                    float labelY = y + 25;
                    if (labelY > height - axisPadding) {  // 标签会超出底部
                        labelY = height - axisPadding - 5;  // 固定在底部边界内
                    }
                    if (labelY < axisPadding + 20) {  // 标签会超出顶部
                        labelY = axisPadding + 20;  // 固定在顶部边界内
                    }
                    canvas.drawText(String.valueOf(value), x, labelY, valuePaint2);
                }
            } else {
                Path path2 = new Path();
                int dataSize2 = dataBuffer2.size();
                int effectiveSize2 = Math.max(dataSize2, 2);

                for (int i = 0; i < dataBuffer2.size(); i++) {
                    int value = dataBuffer2.get(i);
                    float x = axisPadding + labelPadding + i * (axisWidth / (effectiveSize2 - 1));
                    float y = height - axisPadding - (value - minY) * yScale;
                    if (i == 0) {
                        path2.moveTo(x, y);
                    } else {
                        path2.lineTo(x, y);
                    }

                    // 如果启用了标签显示，绘制数据点圆圈和标签
                    if (showDataLabels) {
                        Paint pointPaint2 = new Paint();
                        pointPaint2.setColor(plotColor2);
                        pointPaint2.setStyle(Paint.Style.FILL);
                        canvas.drawCircle(x, y, 5, pointPaint2);

                        // 调整标签位置，确保不超出边界
                        float labelY = y + 25;
                        if (labelY > height - axisPadding) {  // 标签会超出底部
                            labelY = height - axisPadding - 5;  // 固定在底部边界内
                        }
                        if (labelY < axisPadding + 20) {  // 标签会超出顶部
                            labelY = axisPadding + 20;  // 固定在顶部边界内
                        }
                        canvas.drawText(String.valueOf(value), x, labelY, valuePaint2);
                    }
                }
                canvas.drawPath(path2, plotPaint2);
            }
        }
    }
}
