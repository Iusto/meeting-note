# 03. 시스템 아키텍처

웹앱은 REST로 회의·녹음·사전을 관리하고 Mock 전사 또는 실제 AI를 선택한다. LIVE 모드는 인증된 내부 WebSocket으로 PCM16 16kHz mono 프레임을 서버에 보내고, 서버가 Deepgram과 OpenAI를 호출한다. 구현과 실측 상태는 [개발 계획](08-IMPLEMENTATION-PLAN.md)을 따른다.

## 구조를 선택한 이유
**React SPA + Spring Boot 모듈러 모놀리식 + PostgreSQL + 디스크 + Deepgram(STT 후보) + OpenAI Sol(요약·교정)**. 로컬 Python/STT/LLM Worker **없음**. 공급자 의존성은 `SpeechProvider` 인터페이스로 격리하되 복잡한 자동 AI Router는 만들지 않는다.

개인 홈서버 한 대에서 운영하므로 배포·백업 단위를 단순하게 유지한다. 관계와 수정 이력은 PostgreSQL에, 큰 오디오 파일은 디스크에 둔다. 브라우저가 공급자에 직접 접속하지 않아 키·예산·회의 시간축을 서버에서 관리할 수 있다.

```mermaid
flowchart TD
  WEB[React TypeScript
브라우저 마이크+로컬 녹음] -->|REST 설정·회의 조회| API[Spring Boot API]
  WEB <-->|인증된 내부 WebSocket
오디오 청크·전사 이벤트| RELAY[Spring Boot STT Relay]
  RELAY <-->|server-to-server WebSocket| DG[Deepgram Nova-3
실시간 STT + 화자 + keyterm]
  WEB -->|녹음 청크 업로드| API
  API --> DB[(PostgreSQL)]
  API --> FILES[(로컬 오디오 파일)]
  API --> JOB[비동기 교정·요약 작업]
  JOB --> SOL[OpenAI Responses
gpt-6.1-sol]
  JOB --> DB
```

## 경계·역할
- **브라우저:** 하나의 `getUserMedia` 스트림을 MediaRecorder 원본 녹음과 AudioWorklet PCM16 16kHz mono 전사 송신에 함께 사용한다. 직접 Deepgram/OpenAI 장기 키에 접근하지 않는다.
- **Spring Boot:** 회의 상태/인증/저장소/비동기 작업/비용 기록, 오디오 WebSocket relay. 실시간 스트림에 DB 또는 LLM이 블로킹 영향을 주지 않도록 메모리 큐·별도 실행자/백프레셔 설정.
- **Deepgram:** 첫 후보 `model=nova-3&language=ko&interim_results=true&diarize_model=latest&keyterm=...`. 실제 endpoint/파라미터 계약은 구현 당시 문서에 따라 검증. `diarize_model=v2`는 **스트리밍 불가**.
- **OpenAI:** 요약·교정·용어 후보에만 사용. `gpt-6.1-sol` Standard / Responses / 구조화 출력.
- **DB:** 메타데이터, 확정 발언, 화자, 교정 버전, 요약, 사용량.
- **디스크:** 녹음 청크·합쳐진 녹음, SHA-256, 보관 기간 설정. 외부 공개 금지.

## Streaming 핵심 흐름
1. 시작 전 인증·예산·마이크·주제 용어 검증.
2. 서버가 Deepgram WebSocket 오픈. 브라우저와 서버 사이 인증된 별도 WebSocket으로 오디오 프레임 전송.
3. 브라우저 녹음은 STT 송신 실패와 **무관하게 지속**. 일정 크기·시간으로 나눠 서버 업로드; 전송 실패 시 재시도 가능하게 임시 저장 방안 검증.
4. Deepgram 단어/문장 interim & final, speaker ID, timestamp를 **공급자별 adapter**가 내부 이벤트로 변환.
5. UI는 interim 즉시 표시. final만 장기 DB 저장(이벤트 중복 방지 키 및 offset).
6. 문장 확정 후 안전한 사전 검사 → 선택적 Sol 교정. 사용자 고정 문장은 AI가 변경 불가.
7. 종료 시 오디오 청크 finalize, 전사와 시간축 정합성 검사, 요약·결정/할 일 작성. 불확실한 구간을 사용자 검토 큐로.

## 처리 시간/운영 한계
- P95 첫 자막 3초 이내 목표: 원격 STT 결과+WebSocket roundtrip+UX를 **집에서 측정**해야 함. 선언만으로 보장 불가.
- 동시 접속은 **사용자 1명·회의 1개**. 연결 수/월 예산과 분당 API 비용을 제한.
- 3~4시간 회의에서 provider WebSocket 또는 집 인터넷 끊길 수 있음; 회의 내 `streamSessionId`와 `audioOffsetMs`로 재연결·시간 매핑.
- 장시간 녹음 포맷/코덱과 timestamp 드리프트는 별도 검증 필요.
- PC 오디오(Teams/Zoom) 캡처는 브라우저/OS 권한 제약으로 **MVP 범위 밖**, 대면 마이크부터.

## 공급자 소유권 보호
선택적인 `DeepgramStartupRecovery`는 전용 PostgreSQL 세션의 advisory lock을 감시하고 예약·전송·연결 직전에 소유권을 재확인한다. 잠금·연결·backend PID가 바뀌면 해당 인스턴스의 guarded STT만 재시작 전까지 차단한다. 활성 STT는 `OWNERSHIP_LOST`로 종료하고 전송된 비용은 `UNCERTAIN`으로 남긴다. 회의와 로컬 녹음은 계속된다.

감시 주기는 기본 1초, JDBC query/network 제한은 2초/3초다. 유료 모드에서 시작 복구와 소유권 확인을 활성화하고, 소유권 유실은 공급자 연결과 후속 저장을 차단한다. 잠금 유실·재시작 경합 검증 결과는 [07](07-TEST-AND-EVAL.md)에 기록한다. 한국어 품질은 같은 앱의 실사용에서 확인한다.

## 추후 확장시 고려, 지금은 배제
Redis, Kafka, K8s, 벡터 DB, 로컬 pyannote, GPU 서버, WebRTC 별도 SFU, 테넌트·결제 시스템. 공급자 변경이 실제 필요할 때만 second adapter 추가.
