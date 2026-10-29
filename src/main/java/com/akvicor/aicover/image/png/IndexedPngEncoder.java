package com.akvicor.aicover.image.png;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** 将完成透明度处理的 8 位索引图写成非交错 PNG，保持调色板及像素索引不变。 */
final class IndexedPngEncoder {
    private IndexedPngEncoder() {
    }

    static byte[] encode(BufferedImage image) throws IOException {
        var palette = (IndexColorModel) image.getColorModel();
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeLong(0x89504e470d0a1a0aL);
            var header = ByteBuffer.allocate(13);
            header.putInt(image.getWidth());
            header.putInt(image.getHeight());
            // 8 位索引色、标准 Deflate 与过滤方法、非交错。
            header.put(new byte[] {8, 3, 0, 0, 0});
            writeChunk(output, "IHDR", header.array());

            byte[] colors = new byte[palette.getMapSize() * 3];
            for (int i = 0; i < palette.getMapSize(); i++) {
                colors[i * 3] = (byte) palette.getRed(i);
                colors[i * 3 + 1] = (byte) palette.getGreen(i);
                colors[i * 3 + 2] = (byte) palette.getBlue(i);
            }
            writeChunk(output, "PLTE", colors);
            writeChunk(output, "IDAT", compressRows(image));
            writeChunk(output, "IEND", new byte[0]);
        }
        return bytes.toByteArray();
    }

    private static byte[] compressRows(BufferedImage image) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (var output = new DeflaterOutputStream(bytes, deflater)) {
            byte[] row = new byte[image.getWidth()];
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRaster().getDataElements(0, y, image.getWidth(), 1, row);
                // PNG 规范 §12.7 建议索引色使用 None；索引差值不代表色差，
                // 真彩色的自适应过滤会破坏重复索引序列，实测显著增大封面文件。
                output.write(0);
                output.write(row);
            }
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }

    /** PNG 块的 CRC 覆盖类型与数据；长度与 CRC 均按网络字节序输出。 */
    private static void writeChunk(DataOutputStream output, String type, byte[] data)
        throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        output.writeInt(data.length);
        output.write(typeBytes);
        output.write(data);
        output.writeInt((int) crc.getValue());
    }
}
