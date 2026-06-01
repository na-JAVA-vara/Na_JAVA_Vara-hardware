package com.vara.hardware.rfid.model;

/**
 * Immutable value object representing a single card-read event from the RFID reader.
 * Produced by SerialReader and consumed by AccessController.
 */
public final class RFIDEvent {

    private final String uid;
    private final long   timestamp;  // System.currentTimeMillis() at the moment the frame was parsed

    public RFIDEvent(String uid, long timestamp) {
        this.uid       = uid;
        this.timestamp = timestamp;
    }

    public String getUid()       { return uid; }
    public long   getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return "RFIDEvent{uid='" + uid + "', timestamp=" + timestamp + '}';
    }
}
