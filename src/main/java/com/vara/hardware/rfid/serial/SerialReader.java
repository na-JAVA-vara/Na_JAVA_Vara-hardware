package com.vara.hardware.rfid.serial;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import com.vara.hardware.rfid.access.AccessController;
import com.vara.hardware.rfid.model.RFIDEvent;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Reads raw bytes from a USB/UART RFID reader and converts them to RFIDEvent objects.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Threading model                                                        │
 * │                                                                         │
 * │  [JSerialComm event thread]                                             │
 * │    serialEvent()  ──►  FrameParser.feedBytes()  ──►  queue.offer()     │
 * │    (fast, non-blocking; never calls DB or AccessController)             │
 * │                                   │                                     │
 * │                        LinkedBlockingQueue<RFIDEvent>                   │
 * │                        (capacity = QUEUE_CAP)                           │
 * │                                   │                                     │
 * │  [rfid-event-processor thread]    │                                     │
 * │    processQueue()  ◄──────────────┘                                     │
 * │      └──►  accessController.handleRFIDEvent()                           │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * The queue decouples hardware I/O speed from business-logic latency, so bursts
 * of rapid card taps are buffered and processed in order without any loss.
 */
public final class SerialReader implements SerialPortDataListener {

    private static final Logger LOG = Logger.getLogger(SerialReader.class.getName());

    private static final int  BAUD_RATE       = 9600;
    private static final int  QUEUE_CAP       = 256;   // drop-safe: RFID taps are slow (~10/s max)
    private static final long POLL_TIMEOUT_MS = 200;

    private final SerialPort       port;
    private final AccessController accessController;

    // --- Shared between JSerialComm event thread and processorExecutor ---
    private final BlockingQueue<RFIDEvent> eventQueue;
    private final AtomicBoolean            running;

    private final ExecutorService processorExecutor;

    // Accessed only from the JSerialComm event thread — no synchronization needed
    private final FrameParser frameParser;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * @param portName        e.g. "COM3" (Windows) or "/dev/ttyUSB0" (Linux)
     * @param accessController receives parsed UID events
     * @param hasLengthByte   {@code true} if the reader frame includes a payload-length byte
     */
    public SerialReader(String portName, AccessController accessController, boolean hasLengthByte) {
        this.port              = SerialPort.getCommPort(portName);
        this.accessController  = accessController;
        this.eventQueue        = new LinkedBlockingQueue<>(QUEUE_CAP);
        this.running           = new AtomicBoolean(false);
        this.processorExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rfid-event-processor");
            t.setDaemon(true);
            return t;
        });
        this.frameParser = new FrameParser(hasLengthByte);
    }

    /** Convenience constructor for readers that include a length byte (most common). */
    public SerialReader(String portName, AccessController accessController) {
        this(portName, accessController, true);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Configures the port, registers the data listener, and starts the processor thread.
     *
     * @return {@code true} if the port was opened successfully
     */
    public boolean open() {
        configurePort();
        if (!port.openPort()) {
            LOG.severe("포트 열기 실패: " + port.getSystemPortName());
            return false;
        }
        running.set(true);
        port.addDataListener(this);
        processorExecutor.submit(this::processQueue);
        LOG.info("SerialReader 시작 — 포트: " + port.getSystemPortName()
                 + "  " + BAUD_RATE + " 8N1");
        return true;
    }

    /**
     * Gracefully shuts down the processor thread, removes the listener, and closes the port.
     * Waits up to 3 s for any in-flight queue items to be processed before forcing shutdown.
     */
    public void close() {
        running.set(false);
        port.removeDataListener();
        port.closePort();
        processorExecutor.shutdown();
        try {
            if (!processorExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                processorExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            processorExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        frameParser.reset();
        LOG.info("SerialReader 종료 완료");
    }

    // -------------------------------------------------------------------------
    // SerialPortDataListener — JSerialComm event thread
    // -------------------------------------------------------------------------

    @Override
    public int getListeningEvents() {
        return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
    }

    /**
     * Called by JSerialComm's internal thread whenever bytes are available.
     *
     * Contract: must return as fast as possible.
     * Never blocks, never touches DB, never calls AccessController directly.
     */
    @Override
    public void serialEvent(SerialPortEvent event) {
        if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) return;

        int available = port.bytesAvailable();
        if (available <= 0) return;

        byte[] raw  = new byte[available];
        int    read = port.readBytes(raw, available);
        if (read <= 0) return;

        List<String> uids = frameParser.feedBytes(raw, read);
        for (String uid : uids) {
            RFIDEvent rfidEvent = new RFIDEvent(uid, System.currentTimeMillis());
            if (!eventQueue.offer(rfidEvent)) {
                // Queue full: a burst of 256+ taps within one processing cycle — extremely rare.
                // Log and discard; do NOT block the hardware event thread.
                LOG.warning("이벤트 큐 포화 — UID 폐기: " + uid);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Consumer — rfid-event-processor thread
    // -------------------------------------------------------------------------

    /**
     * Drains the event queue one item at a time and forwards each to AccessController.
     * Runs until running=false AND the queue is fully drained.
     */
    private void processQueue() {
        LOG.info("이벤트 처리 스레드 시작");
        while (running.get() || !eventQueue.isEmpty()) {
            try {
                RFIDEvent event = eventQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (event != null) {
                    accessController.handleRFIDEvent(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        LOG.info("이벤트 처리 스레드 종료");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void configurePort() {
        port.setBaudRate(BAUD_RATE);
        port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);
        // Non-blocking mode: reads return immediately with whatever bytes are available.
        // The data listener drives all reads; we never poll.
        port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
    }

    public boolean isOpen() { return port.isOpen(); }

    public int queueSize()  { return eventQueue.size(); }

    @Override
    public String toString() {
        return "SerialReader{port=" + port.getSystemPortName()
               + ", open=" + port.isOpen()
               + ", queueSize=" + eventQueue.size() + '}';
    }
}
