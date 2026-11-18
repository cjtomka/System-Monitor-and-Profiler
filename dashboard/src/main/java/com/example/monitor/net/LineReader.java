package com.example.monitor.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Reads '\n'-terminated UTF-8 lines with a hard length limit. Not thread-safe. */
final class LineReader {
    private final InputStream in;
    private final int maxLine;
    private byte[] buf = new byte[8192];
    private int start = 0;     // first byte of the line being assembled
    private int scan = 0;      // next byte to look at for a newline
    private int end = 0;       // end of valid data

    LineReader(InputStream in, int maxLine) {
        this.in = in;
        this.maxLine = maxLine;
    }

    /** The next line without its terminator, or null at end of stream (a partial last line is dropped). */
    String readLine() throws IOException {
        for (;;) {
            for (; scan < end; scan++) {
                if (buf[scan] == '\n') {
                    int lineEnd = scan;
                    if (lineEnd > start && buf[lineEnd - 1] == '\r') lineEnd--;
                    String line = new String(buf, start, lineEnd - start, StandardCharsets.UTF_8);
                    start = scan + 1;
                    scan = start;
                    return line;
                }
            }
            if (end - start > maxLine) throw new IOException("line longer than " + maxLine + " bytes");

            if (start > 0) {                                   // move the partial line to the front
                System.arraycopy(buf, start, buf, 0, end - start);
                end -= start;
                scan -= start;
                start = 0;
            }
            if (end == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);   // growth is bounded by maxLine

            int n = in.read(buf, end, buf.length - end);
            if (n < 0) return null;
            end += n;
        }
    }
}