package com.vara.hardware.rfid;

import com.fazecast.jSerialComm.SerialPort;
import com.vara.hardware.rfid.access.AccessController;
import com.vara.hardware.rfid.model.AccessResult;
import com.vara.hardware.rfid.model.RFIDEvent;
import com.vara.hardware.rfid.model.User;
import com.vara.hardware.rfid.repository.InMemoryUserRepository;
import com.vara.hardware.rfid.repository.UserRepository;
import com.vara.hardware.rfid.serial.SerialReader;

/**
 * Entry point — demonstrates wiring SerialReader ↔ AccessController.
 *
 * Usage:
 *   mvn package
 *   java -jar target/vara-hardware-1.0.0-SNAPSHOT.jar [PORT]
 *
 * PORT defaults to "COM3" (Windows).  Linux: "/dev/ttyUSB0"
 */
public class Main {

    public static void main(String[] args) throws InterruptedException {

        // ── 1. 연결 가능한 포트 목록 출력 ────────────────────────────────────────
        System.out.println("=== 시스템에서 감지된 시리얼 포트 ===");
        SerialPort[] ports = SerialPort.getCommPorts();
        if (ports.length == 0) {
            System.out.println("  (없음)");
        }
        for (SerialPort p : ports) {
            System.out.printf("  %-12s  %s%n", p.getSystemPortName(), p.getPortDescription());
        }
        System.out.println();

        // ── 2. 사용자 저장소 초기화 ───────────────────────────────────────────────
        // 실제 환경에서는 JdbcUserRepository / JpaUserRepository로 교체
        UserRepository repo = new InMemoryUserRepository();
        repo.save(new User("A1B2C3D4", "홍길동", true));
        repo.save(new User("11223344", "김철수", false));  // 비활성 계정
        repo.save(new User("DEADBEEF", "이영희", true));

        // ── 3. AccessController 생성 (onAccessResult 재정의 예시) ─────────────────
        AccessController controller = new AccessController(repo) {

            @Override
            protected void onAccessResult(RFIDEvent event, AccessResult result) {
                super.onAccessResult(event, result);   // 기본 로그 출력

                if (result.isGranted()) {
                    // TODO: GPIO.triggerRelay(doorId, 200);   // 200ms 잠금 해제
                    // TODO: auditLogRepository.insert(event, result);
                } else {
                    // TODO: buzzer.beep(2, 100);
                    // TODO: auditLogRepository.insert(event, result);
                }
            }
        };

        // ── 4. SerialReader 시작 ──────────────────────────────────────────────────
        // hasLengthByte=true: 리더기 프레임이 [STX][LEN][UID...][BCC][ETX] 구조인 경우
        // hasLengthByte=false: [STX][UID...][BCC][ETX] 구조인 경우 false로 변경
        String portName = args.length > 0 ? args[0] : "COM3";
        SerialReader reader = new SerialReader(portName, controller, true);

        if (!reader.open()) {
            System.err.println("[ERROR] 포트를 열 수 없습니다: " + portName);
            System.err.println("        올바른 포트 이름을 인수로 전달하세요.");
            controller.shutdown();
            System.exit(1);
        }

        System.out.println("RFID 리더기 대기 중... (Ctrl+C 로 종료)");

        // ── 5. JVM 종료 훅 ────────────────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n종료 신호 수신 — 리소스 정리 중...");
            reader.close();
            controller.shutdown();
            System.out.println("정상 종료");
        }, "shutdown-hook"));

        // ── 6. 메인 스레드 유지 (데몬 스레드가 살아있도록) ────────────────────────
        Thread.currentThread().join();
    }
}
