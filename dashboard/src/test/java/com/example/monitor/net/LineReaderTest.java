package com.example.monitor.net;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LineReaderTest {

    /** Delivers one byte per read, the worst case for line splitting. */
    private static InputStream trickle(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return new InputStream() {
            int i = 0;
            @Override public int read() { return i < b.length ? b[i++] & 0xFF : -1; }
            @Override public int read(byte[] buf, int off, int len) {
                if (i >= b.length) return -1;
                if (len == 0) return 0;
                buf[off] = b[i++];
                return 1;
            }
        };
    }

    @Test
    void splitsLinesAcrossTinyReads() throws IOException {
        LineReader r = new LineReader(trickle("one\ntwo\n\nthree\n"), 1024);
        assertEquals("one", r.readLine());
        assertEquals("two", r.readLine());
        assertEquals("", r.readLine());
        assertEquals("three", r.readLine());
        assertNull(r.readLine());
    }

    @Test
    void stripsCarriageReturns() throws IOException {
        LineReader r = new LineReader(trickle("a\r\nb\n"), 1024);
        assertEquals("a", r.readLine());
        assertEquals("b", r.readLine());
    }

    @Test
    void keepsMultibyteCharactersIntact() throws IOException {
        LineReader r = new LineReader(trickle("caf\u00e9 \u20ac\n"), 1024);   // 2- and 3-byte sequences
        assertEquals("caf\u00e9 \u20ac", r.readLine());
    }

    @Test
    void dropsAPartialLastLine() throws IOException {
        LineReader r = new LineReader(new ByteArrayInputStream("complete\npartial".getBytes(StandardCharsets.UTF_8)), 1024);
        assertEquals("complete", r.readLine());
        assertNull(r.readLine());
    }

    @Test
    void refusesOverlongLines() {
        LineReader r = new LineReader(trickle("x".repeat(100)), 16);
        assertThrows(IOException.class, r::readLine);
    }

    @Test
    void handlesLinesLargerThanTheInitialBuffer() throws IOException {
        String big = "y".repeat(50_000);
        LineReader r = new LineReader(new ByteArrayInputStream((big + "\nnext\n").getBytes(StandardCharsets.UTF_8)), 100_000);
        assertEquals(big, r.readLine());
        assertEquals("next", r.readLine());
    }
}
