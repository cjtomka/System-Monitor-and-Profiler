package com.example.monitor.net;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SocketPathsTest {

    @Test
    void anExplicitPathWinsOverTheEnvironment() {
        assertEquals(Path.of("explicit.sock"), SocketPaths.resolve("explicit.sock", "env.sock"));
    }

    @Test
    void theEnvironmentValueIsUsedWhenThereIsNoExplicitPath() {
        assertEquals(Path.of("env.sock"), SocketPaths.resolve(null, "env.sock"));
        assertEquals(Path.of("env.sock"), SocketPaths.resolve("", "env.sock"));
    }

    @Test
    void blankValuesAreIgnored() {
        assumeTrue(System.getenv("LOCALAPPDATA") != null, "needs LOCALAPPDATA, which Windows always sets");
        assertEquals(SocketPaths.defaultPath(), SocketPaths.resolve("  ", ""));
    }

    @Test
    void theDefaultIsUsedLast() {
        assumeTrue(System.getenv("LOCALAPPDATA") != null, "needs LOCALAPPDATA, which Windows always sets");
        Path p = SocketPaths.resolve(null, null);
        assertEquals(SocketPaths.defaultPath(), p);
        assertTrue(p.endsWith(Path.of("monitord", "monitor.sock")));
    }
}
