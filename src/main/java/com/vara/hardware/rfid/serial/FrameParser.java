package com.vara.hardware.rfid.serial;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateful byte-by-byte parser for the STX/ETX framing used by most UART RFID readers.
 *
 * Frame layout (hasLengthByte = true):
 *   [STX 0x02] [LEN 1B] [UID_BYTES LEN*B] [BCC 1B] [ETX 0x03]
 *
 * Frame layout (hasLengthByte = false):
 *   [STX 0x02] [UID_BYTES N*B] [BCC 1B] [ETX 0x03]
 *
 * BCC = XOR of all UID bytes (not including STX, LEN, or ETX).
 *
 * NOT thread-safe — designed to be called exclusively from the JSerialComm event thread.
 */
class FrameParser {

    static final byte STX = 0x02;
    static final byte ETX = 0x03;

    private enum State { IDLE, COLLECTING }

    private final boolean hasLengthByte;
    private final byte[]  frameBuf = new byte[64];
    private       int     frameLen;
    private       State   state    = State.IDLE;

    FrameParser(boolean hasLengthByte) {
        this.hasLengthByte = hasLengthByte;
    }

    /**
     * Feed a chunk of raw bytes; returns UID hex strings for every complete, valid frame found.
     * Typically returns 0 or 1 UIDs per chunk, but may return more when reads are batched.
     */
    List<String> feedBytes(byte[] data, int length) {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            String uid = feedOne(data[i]);
            if (uid != null) results.add(uid);
        }
        return results;
    }

    /** Reset state on port reconnect or timeout. */
    void reset() {
        state    = State.IDLE;
        frameLen = 0;
    }

    // -------------------------------------------------------------------------

    private String feedOne(byte b) {
        switch (state) {
            case IDLE:
                if (b == STX) {
                    frameLen = 0;
                    state = State.COLLECTING;
                }
                return null;

            case COLLECTING:
                if (b == ETX) {
                    String uid = extractAndValidate();
                    state    = State.IDLE;
                    frameLen = 0;
                    return uid;  // null if BCC mismatch or frame too short
                }
                if (b == STX) {
                    // Unexpected STX mid-frame: re-sync
                    frameLen = 0;
                    return null;
                }
                if (frameLen < frameBuf.length) {
                    frameBuf[frameLen++] = b;
                } else {
                    // Buffer overflow — discard and re-sync
                    state    = State.IDLE;
                    frameLen = 0;
                }
                return null;

            default:
                return null;
        }
    }

    /**
     * Validates the BCC and converts the UID bytes to an uppercase hex string.
     * Returns null on validation failure.
     */
    private String extractAndValidate() {
        // Need at least: [LEN(opt)] + 1 UID byte + 1 BCC
        int minLen = hasLengthByte ? 3 : 2;
        if (frameLen < minLen) return null;

        int uidStart = hasLengthByte ? 1 : 0;
        int bccIdx   = frameLen - 1;

        // BCC validation: XOR of UID bytes
        byte expected = 0;
        for (int i = uidStart; i < bccIdx; i++) expected ^= frameBuf[i];

        if (expected != frameBuf[bccIdx]) {
            System.err.printf("[FrameParser] BCC 불일치 — 수신 0x%02X, 계산 0x%02X%n",
                              frameBuf[bccIdx] & 0xFF, expected & 0xFF);
            return null;
        }

        int uidLen = hasLengthByte
                   ? (frameBuf[0] & 0xFF)
                   : (bccIdx - uidStart);

        // Guard: declared length must fit inside the buffer
        if (uidStart + uidLen > bccIdx) return null;

        StringBuilder sb = new StringBuilder(uidLen * 2);
        for (int i = uidStart; i < uidStart + uidLen; i++) {
            sb.append(String.format("%02X", frameBuf[i] & 0xFF));
        }
        return sb.toString();
    }
}
