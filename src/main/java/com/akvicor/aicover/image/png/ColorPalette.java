package com.akvicor.aicover.image.png;

import java.awt.image.IndexColorModel;

/** 为完成透明度处理的 RGB 图片提供自适应调色板及颜色距离计算。 */
final class ColorPalette {
    private final int[] colors;
    private final double[][] points;

    ColorPalette(int[] colors) {
        this.colors = colors;
        this.points = new double[colors.length][3];
        for (int i = 0; i < colors.length; i++) {
            toPoint(colors[i], points[i]);
        }
    }

    static void toPoint(int rgb, double[] point) {
        point[0] = rgb >>> 16 & 255;
        point[1] = rgb >>> 8 & 255;
        point[2] = rgb & 255;
    }

    static int toRgb(double[] point) {
        int red = (int) Math.round(point[0]);
        int green = (int) Math.round(point[1]);
        int blue = (int) Math.round(point[2]);
        return 0xff000000 | red << 16 | green << 8 | blue;
    }

    int nearest(double[] point) {
        int nearest = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < colors.length; i++) {
            double distance = 0;
            for (int channel = 0; channel < 3; channel++) {
                double delta = point[channel] - points[i][channel];
                distance += delta * delta;
            }
            if (distance < best) {
                best = distance;
                nearest = i;
            }
        }
        return nearest;
    }

    double[] point(int index) {
        return points[index];
    }

    IndexColorModel colorModel() {
        return new IndexColorModel(8, colors.length, colors, 0, false, -1,
            java.awt.image.DataBuffer.TYPE_BYTE);
    }
}
