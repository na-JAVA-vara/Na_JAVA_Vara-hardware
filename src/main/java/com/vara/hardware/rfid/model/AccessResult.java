package com.vara.hardware.rfid.model;

/**
 * Immutable result of an access-control decision.
 * Created by AccessController and passed to onAccessResult().
 */
public final class AccessResult {

    public enum Status { GRANTED, DENIED, ERROR }

    private final String uid;
    private final Status status;
    private final String userName;  // non-null only when GRANTED
    private final String reason;    // non-null only when DENIED or ERROR

    private AccessResult(String uid, Status status, String userName, String reason) {
        this.uid      = uid;
        this.status   = status;
        this.userName = userName;
        this.reason   = reason;
    }

    // Factory methods ----------------------------------------------------------

    public static AccessResult granted(String uid, String userName) {
        return new AccessResult(uid, Status.GRANTED, userName, null);
    }

    public static AccessResult denied(String uid, String reason) {
        return new AccessResult(uid, Status.DENIED, null, reason);
    }

    public static AccessResult error(String uid, String errorMessage) {
        return new AccessResult(uid, Status.ERROR, null, errorMessage);
    }

    // Accessors ----------------------------------------------------------------

    public String getUid()      { return uid; }
    public Status getStatus()   { return status; }
    public String getUserName() { return userName; }
    public String getReason()   { return reason; }
    public boolean isGranted()  { return status == Status.GRANTED; }

    @Override
    public String toString() {
        return "AccessResult{uid='" + uid + "', status=" + status
               + (userName != null ? ", user='"   + userName + "'" : "")
               + (reason   != null ? ", reason='" + reason   + "'" : "") + '}';
    }
}
