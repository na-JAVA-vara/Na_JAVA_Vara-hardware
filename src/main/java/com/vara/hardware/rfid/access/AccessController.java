package com.vara.hardware.rfid.access;

import com.vara.hardware.rfid.model.AccessResult;
import com.vara.hardware.rfid.model.RFIDEvent;
import com.vara.hardware.rfid.model.User;
import com.vara.hardware.rfid.repository.UserRepository;

import java.util.Optional;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Receives RFIDEvents from SerialReader and enforces the access-control policy.
 *
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  Threading model                                                         │
 * │                                                                          │
 * │  [rfid-event-processor]                                                  │
 * │    handleRFIDEvent()  ──►  dbExecutor.submit(checkAccess)  (non-block)  │
 * │                                        │                                 │
 * │  [access-ctrl-db-worker pool]          │                                 │
 * │    checkAccess()  ◄────────────────────┘                                 │
 * │      └──►  onAccessResult()    ← override to drive hardware/UI/logs     │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * The async DB pool means a slow database query never backs up the hardware
 * event processor; multiple card taps can be validated concurrently.
 *
 * Extend this class and override {@link #onAccessResult} and/or
 * {@link #checkAccess} to add custom policy (time zones, anti-passback, etc.)
 * without modifying the threading skeleton.
 */
public class AccessController {

    private static final Logger LOG            = Logger.getLogger(AccessController.class.getName());
    private static final int    DB_THREAD_COUNT = 4;

    private final UserRepository  userRepository;
    private final ExecutorService dbExecutor;

    public AccessController(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.dbExecutor = Executors.newFixedThreadPool(DB_THREAD_COUNT, r -> {
            Thread t = new Thread(r, "access-ctrl-db-worker");
            t.setDaemon(true);
            return t;
        });
    }

    // -------------------------------------------------------------------------
    // Entry point — called by rfid-event-processor thread
    // -------------------------------------------------------------------------

    /**
     * Non-blocking entry point.
     * Submits a DB-lookup task to the worker pool and returns immediately,
     * so the event-processor thread can continue draining the queue.
     */
    public final void handleRFIDEvent(RFIDEvent event) {
        LOG.fine("이벤트 수신 — UID: " + event.getUid());
        dbExecutor.submit(() -> {
            AccessResult result = checkAccess(event.getUid());
            onAccessResult(event, result);
        });
    }

    // -------------------------------------------------------------------------
    // Policy layer — runs on access-ctrl-db-worker thread
    // -------------------------------------------------------------------------

    /**
     * Cross-validates the UID against the user repository and returns an access decision.
     *
     * Override to add extra rules:
     *   - Time-based access windows
     *   - Zone/door-level permissions
     *   - Anti-passback (same UID cannot re-enter without exiting first)
     *   - Blacklist / temporary suspension
     *
     * @param uid  uppercase hex UID string, e.g. "A1B2C3D4"
     * @return     access decision; never null
     */
    protected AccessResult checkAccess(String uid) {
        try {
            Optional<User> userOpt = userRepository.findByUid(uid);

            if (userOpt.isEmpty()) {
                return AccessResult.denied(uid, "미등록 카드");
            }

            User user = userOpt.get();

            if (!user.isActive()) {
                return AccessResult.denied(uid, "비활성 계정: " + user.getName());
            }

            // TODO: 추가 정책 구현 지점
            //   예) if (!isWithinAllowedHours())  return AccessResult.denied(uid, "시간 외 접근");
            //   예) if (antiPassback.hasNotExited(uid)) return AccessResult.denied(uid, "APB 위반");

            return AccessResult.granted(uid, user.getName());

        } catch (Exception e) {
            LOG.severe("DB 조회 오류 — UID: " + uid + " | " + e.getMessage());
            return AccessResult.error(uid, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Result hook — override for downstream actions
    // -------------------------------------------------------------------------

    /**
     * Called after every access decision on a db-worker thread.
     *
     * Override this to trigger real-world actions:
     *   - Door relay: GPIO.triggerRelay(doorId, durationMs)
     *   - Audit log:  auditLogRepository.insert(event, result)
     *   - UI alert:   websocketBroadcaster.send(result)
     *   - LED/buzzer: peripheralController.signal(result.isGranted())
     *
     * @param event  the original RFID event (contains hardware timestamp)
     * @param result the access decision
     */
    protected void onAccessResult(RFIDEvent event, AccessResult result) {
        String tag = result.isGranted() ? "[허가]" : "[거부]";
        LOG.info(String.format("%s  UID=%-16s  %s",
                tag,
                result.getUid(),
                result.isGranted()
                    ? "사용자: " + result.getUserName()
                    : "사유: "  + result.getReason()));

        // TODO: 실제 구현 시 아래 주석 해제 후 인터페이스에 맞게 연결
        // if (result.isGranted()) {
        //     doorRelay.unlock(200);          // 200ms 개방
        //     ledController.green(500);
        // } else {
        //     ledController.red(1000);
        //     buzzer.beep(2, 100);
        // }
        // auditLog.record(event, result);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Gracefully shuts down the DB worker pool.
     * In-flight tasks are given up to 5 s to complete before forced termination.
     */
    public void shutdown() {
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
