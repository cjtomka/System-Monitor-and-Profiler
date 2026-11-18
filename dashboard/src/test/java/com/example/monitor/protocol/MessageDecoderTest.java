package com.example.monitor.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MessageDecoderTest {

    private static final String SNAPSHOT =
            "{\"v\":1,\"type\":\"snapshot\",\"seq\":7,\"ts_ms\":1763302801002,\"warming_up\":false,"
            + "\"cpu\":{\"total_pct\":37.5,\"cores_pct\":[41.0,33.2]},"
            + "\"mem\":{\"total_bytes\":17179869184,\"avail_bytes\":6442450944,"
            + "\"commit_bytes\":12884901888,\"commit_limit_bytes\":25769803776},"
            + "\"disks\":[{\"name\":\"C:\",\"total_bytes\":512110190592,\"free_bytes\":201326592000,"
            + "\"read_bps\":1048576,\"write_bps\":262144,\"queue\":0.40}],"
            + "\"top_procs\":[{\"pid\":4120,\"name\":\"chrome.exe\",\"cpu_pct\":12.1,\"rss_bytes\":412000000}],"
            + "\"dropped\":3}";

    @Test
    void decodesAFullSnapshot() throws Exception {
        Message.Snapshot s = assertInstanceOf(Message.Snapshot.class, MessageDecoder.decode(SNAPSHOT));
        assertEquals(7, s.seq());
        assertEquals(1763302801002L, s.tsMs());
        assertFalse(s.warmingUp());
        assertEquals(37.5, s.cpu().totalPct(), 1e-9);
        assertEquals(2, s.cpu().coresPct().size());
        assertEquals(33.2, s.cpu().coresPct().get(1), 1e-9);
        assertEquals(17179869184L, s.mem().totalBytes());
        assertEquals(25769803776L, s.mem().commitLimitBytes());
        assertEquals("C:", s.disks().get(0).name());
        assertEquals(201326592000L, s.disks().get(0).freeBytes());
        assertEquals(0.40, s.disks().get(0).queue(), 1e-9);
        assertEquals(4120, s.topProcs().get(0).pid());
        assertEquals("chrome.exe", s.topProcs().get(0).name());
        assertEquals(3, s.dropped());
    }

    @Test
    void invalidSectionsAreNullNotZeroed() throws Exception {
        String line = "{\"v\":1,\"type\":\"snapshot\",\"seq\":1,\"ts_ms\":2,\"warming_up\":true,"
                + "\"cpu\":null,\"mem\":null,\"disks\":null,\"top_procs\":null,\"dropped\":0}";
        Message.Snapshot s = assertInstanceOf(Message.Snapshot.class, MessageDecoder.decode(line));
        assertTrue(s.warmingUp());
        assertNull(s.cpu());
        assertNull(s.mem());
        assertNull(s.disks());
        assertNull(s.topProcs());
    }

    @Test
    void decodesHistoryChunksWithoutProcessLists() throws Exception {
        String line = "{\"v\":1,\"type\":\"history\",\"count\":2,\"last\":true,\"snapshots\":["
                + "{\"seq\":1,\"ts_ms\":10,\"warming_up\":false,\"cpu\":null,\"mem\":null,\"disks\":[]},"
                + "{\"seq\":2,\"ts_ms\":11,\"warming_up\":false,\"cpu\":null,\"mem\":null,\"disks\":null}]}";
        Message.History h = assertInstanceOf(Message.History.class, MessageDecoder.decode(line));
        assertEquals(2, h.count());
        assertTrue(h.last());
        assertEquals(2, h.snapshots().size());
        assertEquals(2, h.snapshots().get(1).seq());
        assertTrue(h.snapshots().get(0).disks().isEmpty());
        assertNull(h.snapshots().get(0).topProcs());
        assertEquals(0, h.snapshots().get(0).dropped());
    }

    @Test
    void decodesSmallMessages() throws Exception {
        Message.Hello hello = assertInstanceOf(Message.Hello.class, MessageDecoder.decode(
                "{\"v\":1,\"type\":\"hello\",\"daemon\":\"monitord 0.1.0\",\"cores\":8,"
                + "\"interval_ms\":1000,\"history_capacity\":600}"));
        assertEquals("monitord 0.1.0", hello.daemon());
        assertEquals(8, hello.cores());
        assertEquals(1000, hello.intervalMs());
        assertEquals(600, hello.historyCapacity());

        Message.ErrorMessage err = assertInstanceOf(Message.ErrorMessage.class, MessageDecoder.decode(
                "{\"v\":1,\"type\":\"error\",\"code\":\"bad_json\",\"message\":\"The line is not valid JSON.\"}"));
        assertEquals("bad_json", err.code());

        assertEquals(42L, assertInstanceOf(Message.Pong.class,
                MessageDecoder.decode("{\"v\":1,\"type\":\"pong\",\"id\":42}")).id());
        assertNull(assertInstanceOf(Message.Pong.class,
                MessageDecoder.decode("{\"v\":1,\"type\":\"pong\"}")).id());
    }

    @Test
    void toleratesUnknownFieldsAndTypes() throws Exception {
        Message.Hello hello = assertInstanceOf(Message.Hello.class, MessageDecoder.decode(
                "{\"v\":1,\"type\":\"hello\",\"daemon\":\"x\",\"cores\":2,\"interval_ms\":500,"
                + "\"history_capacity\":10,\"brand_new_field\":{\"a\":[1,2]}}"));
        assertEquals(2, hello.cores());

        assertEquals("telemetry2", assertInstanceOf(Message.Unknown.class,
                MessageDecoder.decode("{\"v\":1,\"type\":\"telemetry2\",\"x\":1}")).type());
        assertEquals("", assertInstanceOf(Message.Unknown.class,
                MessageDecoder.decode("{\"v\":1}")).type());
    }

    @Test
    void rejectsOtherProtocolVersions() {
        assertThrows(UnsupportedVersionException.class,
                () -> MessageDecoder.decode("{\"v\":2,\"type\":\"hello\"}"));
        assertThrows(UnsupportedVersionException.class,
                () -> MessageDecoder.decode("{\"v\":\"1\",\"type\":\"hello\"}"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(DecodeException.class, () -> MessageDecoder.decode("not json"));
        assertThrows(DecodeException.class, () -> MessageDecoder.decode(""));
        assertThrows(DecodeException.class, () -> MessageDecoder.decode("[1,2]"));
        assertThrows(DecodeException.class,
                () -> MessageDecoder.decode("{\"v\":1,\"type\":\"snapshot\",\"seq\":\"seven\"}"));
    }
}
