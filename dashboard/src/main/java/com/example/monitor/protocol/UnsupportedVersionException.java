package com.example.monitor.protocol;

/** The daemon speaks a protocol version this client does not. Retrying will not help until one is upgraded. */
public class UnsupportedVersionException extends DecodeException {
    private final String version;

    public UnsupportedVersionException(String version) {
        super("unsupported protocol version " + version, null);
        this.version = version;
    }

    public String version() {
        return version;
    }
}