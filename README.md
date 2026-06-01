## 🔌 하드웨어 및 통신 (Hardware & Communication)

이 모듈은 RFID 리더기와 Java 애플리케이션 간의 빠르고 안정적인 시리얼 통신(USB/UART)을 담당합니다. 하드웨어에서 발생한 센서 데이터는 메인 스레드(UI)의 멈춤 현상(Blocking)을 방지하기 위해 독립적인 백그라운드 스레드 환경에서 수집 및 처리됩니다.

### 🛠 기술 스택
* **Hardware**: RFID 리더기 모듈 (USB/UART 인터페이스 지원 기기) 
* **Library**: `JSerialComm` (플랫폼 독립적 Java 시리얼 포트 제어 라이브러리)

### ✨ 핵심 기능 (Key Features)

* **백그라운드 이벤트 리스닝 (Background Event Listening)**
  * `JSerialComm` 라이브러리를 활용하여 포트로 들어오는 원시(Raw) 데이터를 감지하는 백그라운드 리스너(`SerialReader`)를 구동합니다.
* **스레드 동기화 및 큐잉 (Thread Synchronization & Event Queue)**
  * 다수의 사용자가 짧은 시간 안에 연속으로 카드를 태그할 때 발생할 수 있는 데이터 누락과 애플리케이션 데드락(Deadlock)을 방지하기 위해, 이벤트 큐(Event Queue)와 스레드 동기화(Synchronization) 기법을 엄격하게 적용했습니다.
* **Controller 데이터 파이프라인**
  * [cite_start]하드웨어 계층에서 수집된 고유 식별자(UID) 데이터는 Service Layer를 거쳐 출입 인가를 판별하는 `AccessController`로 즉각적이고 안전하게 전달됩니다[cite: 27, 30].

### ⚙️ 시스템 데이터 흐름 (Data Flow)
1. **[Hardware]** RFID 카드를 리더기에 태그 (UID 발생)
2. **[Service Layer]** `SerialReader`의 백그라운드 스레드가 비동기적으로 데이터 감지 및 수집
3. **[Controller Layer]** 수집된 UID 데이터를 `AccessController`로 전달하여 비즈니스 로직(DB 인가 확인) 수행

### ⚠️ 주의 사항 (Troubleshooting)
* **포트 권한 문제**: Linux/Mac 환경에서 실행 시 시리얼 포트 접근 권한(ex: `/dev/ttyUSB0`)이 필요할 수 있습니다. 권한 거부 오류 발생 시 `chmod` 명령어로 포트 권한을 부여하거나 관리자 권한으로 실행해 주세요.
* **리더기 연결 상태**: 시스템 가동 전 리더기가 정상적으로 USB/UART 포트에 연결되어 있는지 물리적 연결 상태를 반드시 확인해야 합니다.
