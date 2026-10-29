package com.akvicor.aicover.image.png;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.Arrays;

/** 蛇形 Floyd–Steinberg 误差扩散，将量化误差分散到相邻像素以减轻渐变色带。 */
final class ErrorDiffusion {
    /** 限制高反差边缘的误差传播，避免少量稀有颜色形成明显的彩色颗粒。 */
    private static final double MAX_ERROR = 16;

    private ErrorDiffusion() {
    }

    static BufferedImage apply(int[] pixels, int width, int height, ColorPalette palette) {
        var indexed = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED,
            palette.colorModel());
        byte[] indices = ((DataBufferByte) indexed.getRaster().getDataBuffer()).getData();
        double[] current = new double[(width + 2) * 3];
        double[] next = new double[current.length];
        double[] point = new double[3];
        for (int y = 0; y < height; y++) {
            int direction = y % 2 == 0 ? 1 : -1;
            int start = direction == 1 ? 0 : width - 1;
            for (int x = start; x >= 0 && x < width; x += direction) {
                int position = y * width + x;
                ColorPalette.toPoint(pixels[position], point);
                int cursor = (x + 1) * 3;
                for (int channel = 0; channel < 3; channel++) {
                    point[channel] += current[cursor + channel];
                }
                int nearest = palette.nearest(point);
                indices[position] = (byte) nearest;
                double[] quantized = palette.point(nearest);
                for (int channel = 0; channel < 3; channel++) {
                    double error = Math.clamp(point[channel] - quantized[channel],
                        -MAX_ERROR, MAX_ERROR);
                    current[cursor + direction * 3 + channel] += error * 7 / 16;
                    next[cursor - direction * 3 + channel] += error * 3 / 16;
                    next[cursor + channel] += error * 5 / 16;
                    next[cursor + direction * 3 + channel] += error / 16;
                }
            }
            double[] swap = current;
            current = next;
            next = swap;
            Arrays.fill(next, 0);
        }
        return indexed;
    }
}
