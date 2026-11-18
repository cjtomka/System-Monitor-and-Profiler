package com.example.monitor.net;

public enum ConnectionState {
    CONNECTING,          // trying to reach the daemon
    CONNECTED,           // socket is up (the daemon's hello follows)
    WAITING,             // not connected; backing off before the next attempt
    VERSION_MISMATCH,    // the daemon speaks another protocol version; still retrying slowly
    STOPPED              // close() was called
}