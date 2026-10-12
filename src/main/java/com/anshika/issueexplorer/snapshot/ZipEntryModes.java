package com.anshika.issueexplorer.snapshot;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads only central-directory Unix modes, which Java 21 ZipEntry does not expose. */
final class ZipEntryModes {
    private ZipEntryModes() { }

    static List<Boolean> symlinks(Path archive) throws IOException {
        try (var file = new RandomAccessFile(archive.toFile(), "r")) {
            int tailLength = (int) Math.min(file.length(), 65557);
            byte[] tail = new byte[tailLength];
            file.seek(file.length() - tailLength);
            file.readFully(tail);
            ByteBuffer end = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
            int eocd = -1;
            for (int i = tailLength - 22; i >= 0; i--) {
                if (end.getInt(i) == 0x06054b50 && i + 22 + unsignedShort(end, i + 20) == tailLength) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) {
                throw new IOException("Missing ZIP central directory.");
            }
            int count = unsignedShort(end, eocd + 10);
            long size = Integer.toUnsignedLong(end.getInt(eocd + 12));
            long offset = Integer.toUnsignedLong(end.getInt(eocd + 16));
            if (count == 65535 || size == 0xffffffffL || offset == 0xffffffffL) {
                throw new IOException("ZIP64 archives are not supported by the bounded extractor.");
            }
            if (unsignedShort(end, eocd + 4) != 0 || unsignedShort(end, eocd + 6) != 0
                    || unsignedShort(end, eocd + 8) != count
                    || offset + size != file.length() - tailLength + eocd) {
                throw new IOException("Unsupported or inconsistent ZIP central directory.");
            }
            file.seek(offset);
            var result = new ArrayList<Boolean>(count);
            byte[] header = new byte[46];
            for (int i = 0; i < count; i++) {
                if (file.getFilePointer() + header.length > offset + size) {
                    throw new IOException("Truncated ZIP central directory.");
                }
                file.readFully(header);
                ByteBuffer record = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                if (record.getInt(0) != 0x02014b50) {
                    throw new IOException("Invalid ZIP central directory entry.");
                }
                int mode = (int) (Integer.toUnsignedLong(record.getInt(38)) >>> 16);
                result.add((mode & 0170000) == 0120000);
                long next = file.getFilePointer() + unsignedShort(record, 28)
                        + unsignedShort(record, 30) + unsignedShort(record, 32);
                if (next > offset + size) {
                    throw new IOException("Invalid ZIP central directory entry length.");
                }
                file.seek(next);
            }
            if (file.getFilePointer() != offset + size) {
                throw new IOException("Unexpected ZIP central directory data.");
            }
            return List.copyOf(result);
        }
    }

    private static int unsignedShort(ByteBuffer buffer, int offset) {
        return Short.toUnsignedInt(buffer.getShort(offset));
    }
}
