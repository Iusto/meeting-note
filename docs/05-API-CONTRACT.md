# 05. REST API와 실시간 이벤트 계약

> 이 문서는 **우리 애플리케이션의 내부 계약**이다. 외부 Deepgram/OpenAI 공급자 이벤트·parameter의 실제 schema는 개발 당시 공식 문서/SDK에서 확인하고 adapter로 변환한다.

현재 앱은 Mock 전사, 로컬 녹음, 실제 AI LIVE 전사를 제공한다. 경로는 별도 표기가 없으면 `/api/v1` 기준이다.

## 인증·기본 회의 계약 (2026-10-25)
- `GET /api/v1/session/configuration`은 인증 없이 `{setupRequired}`를 반환한다. 최초 `POST /api/v1/session/setup {token,username,password,rememberMe}`에서 기존 접속 토큰으로 단일 소유자 계정을 등록하고 로그인한다. 등록 후 추가 가입과 토큰 로그인은 거절한다.
- `POST /api/v1/session {username,password,rememberMe}`으로 로그인한다. 계정 등록 전의 기존 토큰 요청만 호환한다. `GET`은 인증 여부 확인, `DELETE`는 로그아웃이다. 세션 쿠키는 `HttpOnly; SameSite=Strict`, HTTPS에서 `Secure`이며 기본 12시간·로그인 유지 선택 시 30일이다. 서버 재시작 후에도 DB에 저장된 만료 전 세션은 유효하며 로그아웃하면 폐기한다.
- 비밀번호는 소금값을 적용한 PBKDF2-HMAC-SHA256(600,000회), 세션은 무작위 원문의 SHA-256 해시만 저장한다. 가입·로그인에는 동일한 Origin 검사와 시도 제한을 적용한다. 비밀번호·접속 토큰은 브라우저 저장소에 남기지 않는다.
- 기본 회의 API는 목록/생성/조회, `start`, `stop`, `transcript`, revision 기반 세그먼트 교정이다. 추가 구현 계약은 아래 녹음·사전·관리 절을 따른다.
- 회의 상태는 `CREATED` → `STARTED` → `STOPPED`. 목록과 transcript 응답은 JSON 배열이다.
- `/api/v1/meetings/{id}/live`는 인증과 정확한 Origin 검사를 통과한 연결만 허용한다. MOCK은 가상 이벤트를 전송하고 LIVE는 binary PCM16 16kHz mono를 받아 Deepgram 이벤트로 변환한다. 동일 발언의 partial/final은 같은 `eventId`를 사용하고, 확정 저장 뒤에는 `segmentId`도 제공한다.
- 추가 이벤트 `transcript.corrected`는 원문을 바꾸지 않고 `displayText`, `transcriptStatus=CORRECTED`, 증가한 `revision`을 전달한다. 화자 상태는 별도로 갱신한다.
- Mock은 `stream.stopped`로 자동 종료된다. LIVE는 마이크 시작 뒤 오디오 프레임을 보내며 STT 오류·재연결 중에도 다음 절의 녹음 경로를 유지한다. 계정 설정/등록/로그인·health 외 데이터 API는 인증이 필요하며, 상태 변경 요청에는 허용된 `Origin` 헤더가 필요하다.
- Mock의 마지막 라이브 연결이 끊기거나 로그아웃하면 가상 회의도 종료하고 실행 슬롯을 해제한다. 이미 저장한 확정 발언은 유지한다. 로컬 녹음과 업로드 복구는 별도 경로다.

## API 키 없는 녹음 구현 (2026-10-16)

- 회의 생성: `{title, inputSource?: "MOCK" | "LOCAL_RECORDING" | "LIVE", topicId?}`. 생략 시 Mock이며 응답에 `inputSource`를 포함한다.
- `LOCAL_RECORDING`은 외부 STT/가상 전사 WebSocket을 열지 않는다. 마지막 WebSocket 연결 종료 규칙은 기존 mock 회의에만 해당한다. 로컬 녹음 회의는 명시 종료하거나 로그아웃할 때 실행 슬롯을 해제한다.
- `POST /meetings/{id}/recording-chunks?sequence=0&startMs=0&endMs=2000&sha256=<64hex>&mimeType=audio%2Fwebm`: octet-stream 본문, 최대 8 MiB. 순서가 뒤바뀐 업로드도 허용한다. 동일 순번의 같은 내용·메타데이터 재시도는 멱등이며 다른 내용은 409다. 원본 내용의 SHA-256 불일치는 400이다.
- `POST /meetings/{id}/recording-complete {chunkCount}`: 0부터 연속된 청크 개수·각 파일의 해시/길이·동일 미디어 형식을 검증하고 순서대로 결합한다. 완료 후 추가 청크는 409이며 같은 완료 요청은 멱등이다. 종료 후 남은 청크의 업로드 복구도 허용한다.
- `GET /meetings/{id}/recording`: `{id,meetingId,state,bytes,sha256,durationMs,mimeType,chunkCount}`. 녹음 없음은 404, 저장 중 `UPLOADING`, 완료 `COMPLETED`. 파일 시스템 경로는 노출하지 않는다.
- `GET /meetings/{id}/recording/audio`: 인증된 완료 녹음 다운로드. 녹음 API에도 기존 인증·변경 요청 Origin 검증을 적용한다.
- 브라우저 IndexedDB는 업로드 완료 응답을 받은 청크만 삭제한다. 탭 종료 후 녹음 자체는 중단되며, 저장된 청크만 복구한다. 중단된 MediaRecorder 세션을 새 세션과 합치지 않는다.

## 사전·교정·요약 계약
- `GET/POST /topics`, `PATCH/DELETE /topics/{id}`: 주제 CRUD. 사용 중인 주제 삭제는 거절한다.
- `GET/POST /topics/{id}/terms`, `PATCH/DELETE /topics/{id}/terms/{termId}`: 공유 canonical/의미/별칭과 `PENDING|APPROVED|REJECTED` 상태. 삭제는 해당 주제 연결을 해제하고 고아 용어를 정리한다.
- `POST /topics/{id}/suggest-terms`: 유료 모드에서는 `PENDING / OPENAI`, 기본 모드에서는 Mock 후보를 생성한다. `GET /topics/{id}/keyterms`: 개별 승인된 canonical 문자열 배열, 최대 100개.
- `POST/GET /meetings/{id}/correction-suggestions`, `PATCH /meetings/{id}/correction-suggestions/{suggestionId}`: 유료 모드에서는 OpenAI, 기본 모드에서는 승인 별칭 기반 Mock 제안을 생성한다. 적용 시 revision/span/사용자 잠금과 숫자·원문 범위를 다시 검사한다.
- `GET/POST /meetings/{id}/summary`: 최신 요약 조회/명시적 새 버전 생성. 종료된 회의의 확정 전사가 필요하다. 긴 전사는 분할·병합하고 근거 segment ID와 revision snapshot으로 `stale`을 계산한다. 모르는 담당자·기한은 JSON null이다.
- 후보·교정·요약은 자동 실행하지 않는다. 사용자가 해당 버튼을 누를 때 한 번의 작업을 예약한다.

## 구현된 검색·편집·내보내기 (2026-10-17)
- `GET /meetings?q=`: 제목·최초 원문·표시 문장을 대소문자 구분 없이 부분 검색한다. 최대 200자, 빈 값은 전체 목록이며 `%`, `_`는 일반 문자다.
- `PATCH /meetings/{id} {title,revision}`: 제목 최대 200자, 빈 제목 금지. 편집 시작 revision과 다르면 `409 STALE_REVISION`이다.
- `GET /meetings/{id}/export?format=json|markdown`: 인증된 attachment 다운로드. JSON schemaVersion 1은 `{schemaVersion,meeting,transcript,summary}`이며 summary 미생성은 null이다. transcript의 `id`는 근거 segment ID다. 원문/표시 문장·수정/화자 상태·잠금과 요약 근거/오래됨을 함께 보존한다. 경로나 키는 포함하지 않는다.

## 구현된 사용량·예산 설정 (2026-10-18)
- `GET /settings`와 `PATCH /settings`: `monthlyBudgetUsd`(0–10000, 소수 2자리), `warnAtPercent`(1–100), `perMeetingMaxMinutes`(1–1440), `recordingRetentionDays`(null 또는 1–3650), `revision`. PATCH는 편집 항목 전체와 revision을 받으며 오래된 revision은 409다. 응답의 `paidApiEnabled`는 서버 배포 opt-in 상태이며 UI에서 이를 켤 수 없다.
- `GET /ai/configuration`: 유료 모드와 STT/LLM 가용 여부, 서버 시간·회의 비용 상한을 반환한다. 키 또는 키 일부는 반환하지 않는다.
- `GET /usage?month=YYYY-MM`: UTC 회의 시작 월(미시작은 생성 월)의 완료 녹음 초·bytes를 합산한다. 비용은 요청 예약 월 기준이며, 다른 달 회의에 발생한 해당 월 비용도 포함한다. 월 생략 시 현재 UTC 월이다.
- 비용 응답은 `totalEstimatedUsd`, `totalActualUsd`, `reservedUsd`, `budgetCommittedUsd`, `budgetRemainingUsd`, `unreconciledCount`를 구분한다. 공급자 청구를 확인하지 않은 실비는 `null`이며, 외부 작업이 없으면 0이다. 회의별 비용에도 같은 구분을 적용하고, 삭제된 회의의 비용은 월 전체 합계에 보존한다.
- `budgetState`는 예산 0이면 `DISABLED`, 확정·예약 비용 합계에 따라 `OK|WARNING|EXHAUSTED`다. 내부 원장은 요청 ID 멱등성, 동시 예약 차단, 전송 전 예산·시간 재검사, 미확정 청구 보존을 제공한다. 원장 변경용 HTTP API는 제공하지 않는다.
- 보관 일수는 검토 주기이며 자동 삭제를 실행하지 않는다. 예산 소진과 공급자 오류는 새 유료 작업을 막되 이미 진행 중인 로컬 녹음을 중단하지 않는다. 원장 추정액과 공급자 실청구액은 별도로 검증한다.

## 구현된 삭제·보관 검토 (2026-10-17)
- `DELETE /meetings/{id}?revision=N`: 현재 제목 확인 후 명시적 삭제용이다. 음수가 아닌 revision을 요구하며 오래된 revision/활성 회의는 409, unsafe 녹음 디렉터리는 DB 삭제 전에 거절한다. 성공은 `{deleted:true,cleanupPending:boolean}`이다. cleanupPending은 DB 삭제 성공 후 서버 파일 정리가 남은 경우이며 다음 시작에 재시도한다. 브라우저는 성공 응답 후 해당 회의의 복구용 청크를 제거한다.
- 사용량의 회의별 `retentionReviewDue`는 보관 검토 기간이 지났고 녹음 메타데이터가 있는 종료/미시작 회의에만 true다. 자동 삭제를 의미하지 않는다.

## 공통 형식
- REST prefix `/api/v1`; JSON camelCase; 회의 ID UUID; 오디오 timestamp ms; UTC ISO-8601.
- 세션 쿠키 인증과 상태 변경 Origin 검증을 사용한다. 편집은 revision, 녹음 업로드는 청크 순번·해시로 충돌·중복을 처리한다. 범용 `Idempotency-Key` 계약은 현재 제공하지 않는다.
- 공통 오류: `{ "code": "...", "message": "...", "requestId": "..." }`, 키·발언·개인정보 포함 금지.

## 주요 구현 API 요약

상세 본문·충돌 조건은 위 기능별 절을 따른다. 아래 start/stop은 회의 상태 전환이며 서버가 브라우저 마이크를 직접 제어하거나 실제 AI 후처리를 시작하지 않는다.

| 메서드 | 경로 | 내용 |
|---|---|---|
| GET/POST | `/meetings` | 회의 목록/생성 |
| GET/PATCH | `/meetings/{id}` | 상태/제목 조회·변경 |
| POST | `/meetings/{id}/start` | 회의 실행 슬롯 확보·상태 전환 |
| POST | `/meetings/{id}/stop` | 회의 상태와 Mock/LIVE 스트림 종료 |
| POST | `/meetings/{id}/recording-chunks` | chunk 멱등 업로드 및 sha256 확인 |
| POST | `/meetings/{id}/recording-complete` | 파일 조립·무결성 최종화 |
| GET | `/meetings/{id}/transcript` | 발언·화자·수정 상태 타임라인 |
| PATCH | `/meetings/{id}/segments/{segmentId}` | 사용자 표시 문장 교정, `displayText`·`revision` 필수 |
| GET/POST | `/meetings/{id}/summary` | 요약 조회/재생성 명시 요청 |
| GET | `/meetings/{id}/export?format=markdown` | 일관된 snapshot 내보내기, JSON은 `format=json` |
| GET/POST | `/topics` | 주제 목록/생성 |
| GET/POST | `/topics/{id}/terms` | 용어 목록/수동 추가, 상태로 승인·반려 관리 |
| PATCH/DELETE | `/topics/{id}/terms/{termId}` | 용어·별칭·상태 변경/주제 연결 해제 |
| POST | `/topics/{id}/suggest-terms` | 승인 전 Mock/OpenAI 후보 생성 |
| GET | `/topics/{id}/keyterms` | 승인된 표준 용어 최대 100개 |
| GET | `/usage` | 기간별 녹음분·외부 API 예상비 |
| GET/PATCH | `/settings` | 예산 한도, 녹음 보존기간 등 |
| GET | `/ai/configuration` | 서버 AI opt-in·공급자 가용 여부·상한 |

`/meetings/{id}/finalize`와 `/glossary-suggestions/{id}`는 과거 설계안이며 현재 endpoint가 아니다.

## 실시간 WebSocket 계약
- 브라우저 ↔ `GET /api/v1/meetings/{id}/live` WebSocket.
- **서버만** 외부 Deepgram WS 연결과 재연결, 긴 API 키를 관리한다.
- LIVE 클라이언트가 송신: raw PCM16 little-endian 16kHz mono binary frame, JSON `{type:"stream.stop"}` 또는 `{type:"stream.ping"}`.
- 서버가 송신: `session.ready`, `transcript.partial`, `transcript.final`, `speaker.updated`, `stream.warning`, `stream.error`, `stream.stopped`.

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
- 시간축은 `streamSessionId + audioOffsetMs + provider offset`으로 조정한다. 브라우저는 재연결 때 이미 캡처한 회의 상대 시간을 query에 보내며, 끊긴 동안 프레임을 재전송하지 않고 누락 범위를 표시한다.
- 화자 라벨 없는 전사는 정상. 나중에 `speaker.updated`로 갱신하되 불확실한 경우 미확정 유지.
- 서버가 편집/교정 응답을 늦게 받더라도 `revision`이 오래되었으면 자동 덮어쓰지 않는다.
- 오디오 프레임이 들어오는 WebSocket 처리 쓰레드에서 외부 LLM 동기 호출 금지.

## 품질·보안
마이크 접근, 파일 업로드, 비용 가드 모두 서버 검증. 불필요한 원문 텍스트를 로그에 출력하지 않음. 이벤트 payload는 교정 결과까지 schema로 검증.

## 복구·예산 계약 보완 (2026-10-29)

`GET/PATCH /api/v1/settings`에 `perMeetingBudgetUsd`(0.01~10000, 소수 2자리)를 추가한다. PATCH에서 생략하면 기존 값을 보존한다. `GET /api/v1/ai/configuration.maximumMeetingUsd`는 DB의 최신 회의당 예산을 반환한다. V11은 기존 설정에 초기값 3.00을 추가하고 기존 월 예산을 보존한다.

WebSocket `stream.error`에 안전한 사용자 안내용 `message`를 추가한다. 공급자 응답·비밀키·원문 예외는 노출하지 않는다. `101`은 웹앱과 서버 사이 업그레이드 성공이며 공급자 연결 성공은 `session.ready`로 구분한다. 수동 재연결은 기존 녹음을 유지한 새 WebSocket이며 `audioOffsetMs`는 현재 회의 시각부터 시작한다.
