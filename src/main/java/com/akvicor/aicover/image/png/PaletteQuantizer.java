package com.akvicor.aicover.image.png;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** 按颜色分布分割调色板并迭代优化，使用像素权重保留图像主体的颜色与渐变。 */
final class PaletteQuantizer {
    private static final int MAX_COLORS = 256;
    private static final int REFINEMENT_PASSES = 4;

    private PaletteQuantizer() {
    }

    static ColorPalette quantize(int[] pixels) {
        Map<Integer, Bin> histogram = new LinkedHashMap<>();
        Set<Integer> exactColors = new LinkedHashSet<>();
        double[] point = new double[3];
        for (int color : pixels) {
            if (exactColors != null) {
                exactColors.add(color);
                if (exactColors.size() > MAX_COLORS) {
                    exactColors = null;
                }
            }
            ColorPalette.toPoint(color, point);
            int key = (color >>> 19 & 31) << 10
                | (color >>> 11 & 31) << 5 | (color >>> 3 & 31);
            histogram.computeIfAbsent(key, ignored -> new Bin()).add(point);
        }
        if (exactColors != null) {
            return new ColorPalette(exactColors.stream().mapToInt(Integer::intValue).toArray());
        }

        var boxes = new PriorityQueue<Box>(Comparator.comparingDouble(Box::error).reversed());
        histogram.values().forEach(Bin::average);
        boxes.add(new Box(histogram.values().toArray(Bin[]::new)));
        while (boxes.size() < MAX_COLORS && boxes.peek().error() > 0) {
            var box = boxes.remove();
            boxes.addAll(box.split());
        }
        int[] colors = boxes.stream().mapToInt(box -> ColorPalette.toRgb(box.mean)).toArray();
        var palette = new ColorPalette(colors);
        // Lloyd 优化在直方图上按像素数加权，避免对每个原始像素反复搜索。
        for (int pass = 0; pass < REFINEMENT_PASSES; pass++) {
            double[][] sums = new double[colors.length][3];
            long[] counts = new long[colors.length];
            for (var bin : histogram.values()) {
                int nearest = palette.nearest(bin.point);
                counts[nearest] += bin.count;
                for (int channel = 0; channel < 3; channel++) {
                    sums[nearest][channel] += bin.point[channel] * bin.count;
                }
            }
            for (int i = 0; i < colors.length; i++) {
                if (counts[i] == 0) {
                    continue;
                }
                for (int channel = 0; channel < 3; channel++) {
                    sums[i][channel] /= counts[i];
                }
                colors[i] = ColorPalette.toRgb(sums[i]);
            }
            palette = new ColorPalette(colors);
        }
        return palette;
    }

    private static final class Bin {
        private final double[] point = new double[3];
        private long count;

        private void add(double[] value) {
            count++;
            for (int channel = 0; channel < 3; channel++) {
                point[channel] += value[channel];
            }
        }

        private void average() {
            for (int channel = 0; channel < 3; channel++) {
                point[channel] /= count;
            }
        }
    }

    /** 每次优先细分误差最大的颜色区域，而非固定地均分 RGB 空间。 */
    private static final class Box {
        private final Bin[] bins;
        private final double[] mean = new double[3];
        private final double[] variance = new double[3];
        private long count;

        private Box(Bin[] bins) {
            this.bins = bins;
            for (var bin : bins) {
                count += bin.count;
                for (int channel = 0; channel < 3; channel++) {
                    mean[channel] += bin.point[channel] * bin.count;
                }
            }
            for (int channel = 0; channel < 3; channel++) {
                mean[channel] /= count;
                for (var bin : bins) {
                    double delta = bin.point[channel] - mean[channel];
                    variance[channel] += delta * delta * bin.count;
                }
            }
        }

        private double error() {
            return bins.length == 1 ? 0 : Arrays.stream(variance).sum();
        }

        private List<Box> split() {
            int axis = 0;
            for (int channel = 1; channel < 3; channel++) {
                if (variance[channel] > variance[axis]) {
                    axis = channel;
                }
            }
            final int selectedAxis = axis;
            Arrays.sort(bins, Comparator.comparingDouble(bin -> bin.point[selectedAxis]));
            long weight = 0;
            int split = 0;
            do {
                weight += bins[split++].count;
            } while (weight < count / 2 && split < bins.length - 1);
            return List.of(
                new Box(Arrays.copyOfRange(bins, 0, split)),
                new Box(Arrays.copyOfRange(bins, split, bins.length)));
        }
    }
}
