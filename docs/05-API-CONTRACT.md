# 05. 내부 REST + 실시간 이벤트 계약 (초안)

> 이 문서는 **우리 애플리케이션의 내부 계약**이다. 외부 Deepgram/OpenAI 공급자 이벤트·parameter의 실제 schema는 개발 당시 공식 문서/SDK에서 확인하고 adapter로 변환한다.

## Phase 0 구현 범위 (2026-10-16)
- `POST /api/v1/session {"token":"..."}`으로 단일 소유자를 인증한다. `GET`은 인증 여부 확인, `DELETE`는 로그아웃이다. 비밀 토큰은 저장하지 않고 `HttpOnly; SameSite=Strict` 세션 쿠키를 사용한다.
- 구현된 회의 API는 목록/생성/조회, `start`, `stop`, `transcript`, revision 기반 세그먼트 교정이다. 나머지 API는 후속 Phase 계약이며 아직 제공하지 않는다.
- 회의 상태는 `CREATED` → `STARTED` → `STOPPED`. 목록과 transcript 응답은 JSON 배열이다.
- `/api/v1/meetings/{id}/live`는 인증과 정확한 Origin 검사를 통과한 연결에만 가상 이벤트를 전송한다. 동일 발언의 partial/final은 같은 `eventId`를 사용하고, 확정 저장 뒤에는 `segmentId`도 제공한다.
- 추가 이벤트 `transcript.corrected`는 원문을 바꾸지 않고 `displayText`, `transcriptStatus=CORRECTED`, 증가한 `revision`을 전달한다. 화자 상태는 별도로 갱신한다.
- Mock은 `stream.stopped`로 자동 종료된다. 녹음·오디오 프레임·실제 공급자 relay는 구현하지 않는다. health(`/actuator/health`)를 제외한 데이터 API는 인증이 필요하며, 상태 변경 요청에는 허용된 `Origin` 헤더가 필요하다.
- Phase 0에서는 마지막 라이브 연결이 끊기거나 로그아웃하면 가상 회의도 종료하고 실행 슬롯을 해제한다. 이미 저장한 확정 발언은 유지한다. 실녹음과 재연결 복구는 Phase 2 범위다.

## API 키 없는 녹음 구현 (2026-10-16)

- 회의 생성: `{title, inputSource?: "MOCK" | "LOCAL_RECORDING"}`. 생략 시 기존 mock 동작이다. 응답에 `inputSource`를 포함한다.
- `LOCAL_RECORDING`은 외부 STT/가상 전사 WebSocket을 열지 않는다. 마지막 WebSocket 연결 종료 규칙은 기존 mock 회의에만 해당한다. 로컬 녹음 회의는 명시 종료하거나 로그아웃할 때 실행 슬롯을 해제한다.
- `POST /meetings/{id}/recording-chunks?sequence=0&startMs=0&endMs=2000&sha256=<64hex>&mimeType=audio%2Fwebm`: octet-stream 본문, 최대 8 MiB. 순서가 뒤바뀐 업로드도 허용한다. 동일 순번의 같은 내용·메타데이터 재시도는 멱등이며 다른 내용은 409다. 원본 내용의 SHA-256 불일치는 400이다.
- `POST /meetings/{id}/recording-complete {chunkCount}`: 0부터 연속된 청크 개수·각 파일의 해시/길이·동일 미디어 형식을 검증하고 순서대로 결합한다. 완료 후 추가 청크는 409이며 같은 완료 요청은 멱등이다. 종료 후 남은 청크의 업로드 복구도 허용한다.
- `GET /meetings/{id}/recording`: `{id,meetingId,state,bytes,sha256,durationMs,mimeType,chunkCount}`. 녹음 없음은 404, 저장 중 `UPLOADING`, 완료 `COMPLETED`. 파일 시스템 경로는 노출하지 않는다.
- `GET /meetings/{id}/recording/audio`: 인증된 완료 녹음 다운로드. 녹음 API에도 기존 인증·변경 요청 Origin 검증을 적용한다.
- 브라우저 IndexedDB는 업로드 완료 응답을 받은 청크만 삭제한다. 탭 종료 후 녹음 자체는 중단되며, 저장된 청크만 복구한다. 중단된 MediaRecorder 세션을 새 세션과 합치지 않는다.

## 구현된 오프라인 사전·검토 계약 (2026-10-16)
- `GET/POST /topics`, `PATCH/DELETE /topics/{id}`: 주제 CRUD. 사용 중인 주제 삭제는 거절한다.
- `GET/POST /topics/{id}/terms`, `PATCH/DELETE /topics/{id}/terms/{termId}`: 공유 canonical/의미/별칭과 `PENDING|APPROVED|REJECTED` 상태. 삭제는 해당 주제 연결을 해제하고 고아 용어를 정리한다.
- `POST /topics/{id}/suggest-terms`: 고정 MOCK 후보만 생성. `GET /topics/{id}/keyterms`: 개별 승인된 canonical 문자열 배열, 최대 100개.
- `POST/GET /meetings/{id}/correction-suggestions`, `PATCH /meetings/{id}/correction-suggestions/{suggestionId}`: 승인 별칭 기반 MOCK 제안 및 `APPLIED|REJECTED` 검토. 적용 시 revision/span/사용자 잠금을 검사한다.
- `GET/POST /meetings/{id}/summary`: 최신 가상 요약 조회/명시적 새 버전 생성. 종료된 회의의 확정 전사가 필요하다. 근거 segment ID와 revision snapshot으로 `stale`을 계산한다. 모르는 담당자·기한은 JSON null이다.
- 이 절이 현재 구현 계약이다. 아래 전체 HTTP 표에는 아직 구현하지 않은 최종 설계도 포함되어 있다.

## 구현된 검색·편집·내보내기 (2026-10-17)
- `GET /meetings?q=`: 제목·최초 원문·표시 문장을 대소문자 구분 없이 부분 검색한다. 최대 200자, 빈 값은 전체 목록이며 `%`, `_`는 일반 문자다.
- `PATCH /meetings/{id} {title,revision}`: 제목 최대 200자, 빈 제목 금지. 편집 시작 revision과 다르면 `409 STALE_REVISION`이다.
- `GET /meetings/{id}/export?format=json|markdown`: 인증된 attachment 다운로드. JSON schemaVersion 1은 `{schemaVersion,meeting,transcript,summary}`이며 summary 미생성은 null이다. transcript의 `id`는 근거 segment ID다. 원문/표시 문장·수정/화자 상태·잠금과 요약 근거/오래됨을 함께 보존한다. 경로나 키는 포함하지 않는다.

## 구현된 사용량·예산 설정 (2026-10-18)
- `GET /settings`와 `PATCH /settings`: `monthlyBudgetUsd`(0–10000, 소수 2자리), `warnAtPercent`(1–100), `perMeetingMaxMinutes`(1–1440), `recordingRetentionDays`(null 또는 1–3650), `revision`. PATCH는 편집 항목 전체와 revision을 받으며 오래된 revision은 409다. 응답의 `paidApiEnabled`는 false이며 true 요청은 거절한다.
- `GET /usage?month=YYYY-MM`: UTC 회의 시작 월(미시작은 생성 월)의 완료 녹음 초·bytes를 합산한다. 비용은 요청 예약 월 기준이며, 다른 달 회의에 발생한 해당 월 비용도 포함한다. 월 생략 시 현재 UTC 월이다.
- 비용 응답은 `totalEstimatedUsd`, `totalActualUsd`, `reservedUsd`, `budgetCommittedUsd`, `budgetRemainingUsd`, `unreconciledCount`를 구분한다. 공급자 청구를 확인하지 않은 실비는 `null`이며, 외부 작업이 없으면 0이다. 회의별 비용에도 같은 구분을 적용하고, 삭제된 회의의 비용은 월 전체 합계에 보존한다.
- `budgetState`는 예산 0이면 `DISABLED`, 확정·예약 비용 합계에 따라 `OK|WARNING|EXHAUSTED`다. 내부 원장은 요청 ID 멱등성, 동시 예약 차단, 전송 전 예산·시간 재검사, 미확정 청구 보존을 제공한다. 원장 변경용 HTTP API는 제공하지 않는다.
- 보관 일수는 검토 주기이며 자동 삭제를 실행하지 않는다. 유료 작업 시간/예산 설정도 현재 로컬 녹음을 중단하지 않는다. 앱의 실제 공급자 연결은 아직 없으며, 연결 시 원장·요금 계산·실청구 처리를 함께 검증해야 한다.

## 구현된 삭제·보관 검토 (2026-10-17)
- `DELETE /meetings/{id}?revision=N`: 현재 제목 확인 후 명시적 삭제용이다. 음수가 아닌 revision을 요구하며 오래된 revision/활성 회의는 409, unsafe 녹음 디렉터리는 DB 삭제 전에 거절한다. 성공은 `{deleted:true,cleanupPending:boolean}`이다. cleanupPending은 DB 삭제 성공 후 서버 파일 정리가 남은 경우이며 다음 시작에 재시도한다. 브라우저는 성공 응답 후 해당 회의의 복구용 청크를 제거한다.
- 사용량의 회의별 `retentionReviewDue`는 보관 검토 기간이 지났고 녹음 메타데이터가 있는 종료/미시작 회의에만 true다. 자동 삭제를 의미하지 않는다.

## 공통 형식
- REST prefix `/api/v1`; JSON camelCase; 회의 ID UUID; 오디오 timestamp ms; UTC ISO-8601.
- 서버 인증/CSRF 또는 Origin 검증(환경에 따라), `Idempotency-Key`·revision 낙관적 잠금.
- 공통 오류: `{ "code": "...", "message": "...", "requestId": "..." }`, 키·발언·개인정보 포함 금지.

## HTTP API
| 메서드 | 경로 | 내용 |
|---|---|---|
| GET/POST | `/meetings` | 회의 목록/생성 |
| GET/PATCH | `/meetings/{id}` | 상태/제목 조회·변경 |
| POST | `/meetings/{id}/start` | 예산·마이크 준비 확인 뒤 시작 (녹음 서버 상태 전환) |
| POST | `/meetings/{id}/stop` | 녹음 종료, 스트림 종료, 후처리 시작 |
| POST | `/meetings/{id}/recording-chunks` | chunk 멱등 업로드 및 sha256 확인 |
| POST | `/meetings/{id}/recording-complete` | 파일 조립·무결성 최종화 |
| GET | `/meetings/{id}/transcript` | 발언·화자·수정 상태 타임라인 |
| PATCH | `/meetings/{id}/segments/{segmentId}` | 사용자 교정·화자 정정, `revision` 필수 |
| GET/POST | `/meetings/{id}/summary` | 요약 조회/재생성 명시 요청 |
| POST | `/meetings/{id}/finalize` | 검토 완료, 수정 내용 확정 |
| GET/POST | `/topics` | 주제 목록/생성 |
| GET/POST | `/topics/{id}/terms` | 승인된 용어 목록/수동 추가 |
| POST | `/topics/{id}/suggest-terms` | 승인 전 AI 후보만 생성 |
| PATCH | `/glossary-suggestions/{id}` | 승인·반려 |
| GET | `/usage` | 기간별 녹음분·외부 API 예상비 |
| GET/PATCH | `/settings` | 예산 한도, 녹음 보존기간 등 |

## WebSocket (안)
- 브라우저 ↔ `GET /api/v1/meetings/{id}/live` WebSocket.
- **서버만** 외부 Deepgram WS 연결과 재연결, 긴 API 키를 관리한다.
- 인증된 클라이언트가 송신: `audio.frame`, `stream.stop`, `stream.ping` (binary frame 사용 가능; JSON 이벤트의 schema ID와 구분).
- 서버가 수신: `session.ready`, `transcript.partial`, `transcript.final`, `speaker.updated`, `stream.warning`, `stream.error`, `stream.resumed`, `usage.update`.

### 내부 이벤트 예시 (가상의 API payload)
```json
{
  "type": "transcript.final",
  "meetingId": "49a4e6b1-1a8a-4b56-87f7-0199166fabdd",
  "streamSessionId": "37aa6375-3a43-4341-af12-e56035057716",
  "eventId": "provider-event-123",
  "sequence": 42,
  "startMs": 12000,
  "endMs": 16400,
  "speakerLabel": "speaker_1",
  "speakerStatus": "TENTATIVE",
  "text": "이번 로떠 번호 확인해주세요.",
  "final": true
}
```

### 상태/순서/유실 처리
- partial 이벤트는 최신 sequence로 교체. final은 최소 한 번 전달을 가정하여 멱등 저장.
- 시간축은 `streamSessionId + provider offset + accumulated offset`으로 조정, reconnect 뒤 0부터 재시작된 시간 처리.
- 화자 라벨 없는 전사는 정상. 나중에 `speaker.updated`로 갱신하되 불확실한 경우 미확정 유지.
- 서버가 편집/교정 응답을 늦게 받더라도 `revision`이 오래되었으면 자동 덮어쓰지 않는다.
- 오디오 프레임이 들어오는 WebSocket 처리 쓰레드에서 외부 LLM 동기 호출 금지.

## 품질·보안
마이크 접근, 파일 업로드, 비용 가드 모두 서버 검증. 불필요한 원문 텍스트를 로그에 출력하지 않음. 이벤트 payload는 교정 결과까지 schema로 검증.
