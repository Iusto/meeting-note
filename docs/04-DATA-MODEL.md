# 04. PostgreSQL 데이터 설계 (단일 사용자)

현재 스키마는 [Flyway V1–V10](../apps/api/src/main/resources/db/migration)에 있다. 아래는 구현된 테이블의 핵심 필드이며 전체 DDL 복사본은 아니다. 원문·비용·계정 보존의 이유와 제약을 설명한다.

## 원칙
- 서버는 사용자 한 명만을 대상으로 설계. **조직, 멤버십, 결제, 구독, 팀 권한 테이블 없음**.
- 로컬/VPN에서도 단일 소유자 계정으로 인증한다. 최초 등록만 기존 토큰으로 확인하고 이후에는 아이디·비밀번호를 사용한다.
- 업무 데이터는 UUID PK, 단일 소유자·설정 행은 정수 키를 사용한다. 세션은 해시 키를 사용한다. 시간은 `timestamptz`, 오디오 위치는 `start_ms/end_ms` 정수이며 스키마 버전은 Flyway가 관리한다.
- 음성 BLOB은 DB에 저장하지 않는다. 원본·최초 STT 불변, 수정 이력은 추가만 한다.

| 테이블 | 필드(핵심) | 제약/역할 |
|---|---|---|
| `owner_account` | id, username, password_salt, password_hash, created_at | `id=1` 단일 계정, username unique. PBKDF2-HMAC-SHA256 600,000회, 비밀번호 원문 미저장 |
| `auth_sessions` | id_hash, expires_at, created_at | 무작위 세션 쿠키의 SHA-256 해시만 저장. 기본 12시간·로그인 유지 30일, 만료 인덱스 |
| `meeting_topics` | id, name, description, created_at | `name` unique |
| `glossary_terms` | id, canonical, normalized_canonical, meaning, status, source, created_at | 정규화 표준어 unique/승인 상태 |
| `glossary_aliases` | id, term_id, alias, normalized_alias, approved_at | `normalized_alias` 전체 unique. 같은 별칭을 여러 용어에 중복 승인하지 않음 |
| `topic_terms` | topic_id, term_id, priority | compound PK |
| `meetings` | id, title, topic_id, input_source, status, started_at, ended_at, revision | 최근 회의 인덱스 |
| `runtime_state` | id, active_meeting_id | `id=1` 잠금으로 동시 시작·종료 직렬화 |
| `stream_sessions` | id, meeting_id, provider, provider_session_id, audio_offset_ms, started_at, ended_at, close_reason | 재연결/비용 단위 |
| `active_deepgram_sessions` | meeting_id, stream_session_id, request_id, claimed_at | 회의별 활성 POC 세션 1개. stream/request unique FK로 재시작 비용 대사 연결 |
| `recordings` | id, meeting_id, state, path, sha256, bytes, duration_ms | 오디오 파일은 로컬에 저장 |
| `recording_chunks` | recording_id, sequence_no, start_ms, end_ms, sha256, mime_type, bytes, path | `(recording_id, sequence_no)` 복합 PK |
| `speakers` | id, meeting_id, provider_label, display_name, review_state | `(meeting_id, provider_label)` unique |
| `transcript_segments` | id, meeting_id, provider_event_id, start_ms, end_ms, original_text, display_text, transcript_status, speaker_id nullable, speaker_status, revision, user_locked | `(meeting_id, provider_event_id)` unique, time 인덱스 |
| `transcript_revisions` | id, segment_id, before_text, after_text, actor, reason, created_at | 원문 불변, 교정 패치 감사 |
| `correction_suggestions` | id, meeting_id, segment_id, expected_revision, span_start, original_span, replacement, reason, state | PENDING/APPLIED/REJECTED, 승인 시 원문 span·revision 검증 |
| `meeting_summaries` | id, meeting_id, version, provider, content_json, snapshot_json, created_at | 회의별 version unique. 근거와 revision snapshot 보존 |
| `app_settings` | id, monthly_budget_usd, warn_at_percent, per_meeting_max_minutes, recording_retention_days, revision | `id=1`, 설정 변경·비용 예약 잠금 기준 |
| `ai_usage_records` | id(요청 멱등 키), meeting_id nullable, topic_id nullable, provider, model, operation, price_version, state, reservation_month, reserved_usd, estimated_usd, actual_usd nullable, actual_source, maximum_audio_seconds, measured_audio_seconds, input_tokens, output_tokens, created_at, updated_at | 회의·주제 중 하나에 비용을 귀속한다. 삭제 후에도 원장을 보존하며 FK/cascade를 두지 않음 |

용어 후보는 현재 `glossary_terms`의 상태로 관리한다. 결정사항·액션아이템은 `meeting_summaries.content_json`에 포함한다. 별도 `glossary_suggestions`, `action_items`, `decisions`, `processing_jobs` 테이블은 구현하지 않았다. 향후 필요할 때 새 migration으로 도입한다.

## 데이터 정합성 규칙
- 계정·세션은 API 재시작 후 유지한다. 로그아웃·세션 교체는 기존 세션을 폐기하고, 최초 계정 등록은 등록 전 세션을 모두 폐기한다. 백업 복원은 계정을 유지하되 복원된 세션을 삭제한다.
- 한 회의에서 `stream_sessions`가 여러 개일 수 있다(재연결). 이벤트의 기준 시간은 **회의 시작부터 누적된 audio offset**.
- Deepgram 재연결 offset은 마지막 확정 전사의 끝보다 앞설 수 없다. 저장 작업이 시간 초과해도 실제 종료 전까지 활성 claim을 유지한다.
- 유료 모드에서는 PostgreSQL 전용 `DeepgramStartupRecovery`를 활성화한다. 트래픽 수신 전 session advisory lock을 획득하고 경쟁 인스턴스의 기동을 거부한다. 재시작 시 미전송 예약은 RELEASED, 전송 요청은 UNCERTAIN으로 복구한다. 전사·공급자 메타데이터 저장은 같은 트랜잭션에서 활성 claim을 잠그고 확인하므로, 폐기된 연결의 늦은 응답은 저장하지 않는다. Mock 기본 실행에서는 이 복구 기능을 활성화하지 않는다.
- `PARTIAL`은 DB에 항상 저장할 필요가 없음. `FINAL` 이벤트만 durable 기록하며 음성 chunk는 별도로 저장.
- 같은 `provider_event_id`를 재전송해도 중복 INSERT가 발생하지 않도록 UPSERT/멱등.
- `speaker_id`는 nullable. overlap은 여러 `transcript_segments`의 시간 범위 중복으로 표현 가능.
- `original_text`는 변경 금지. 사용자 또는 AI 교정은 `display_text` + `transcript_revisions`/proposal로 관리.
- `source_segment_ids`는 존재 여부와 회의 ID 동일성을 검증한다.
- `glossary_terms`는 누적 회의 수와 무관하게 중복 없이 공유되고, 발음 변형도 alias UNIQUE로 관리.

## DB 제약과 애플리케이션 검증

- 단일 실행 회의 제약을 위해 내부 `runtime_state(id=1, active_meeting_id)` 행을 잠그고 시작/종료를 직렬화한다. PostgreSQL의 `status='STARTED'` 부분 UNIQUE 인덱스도 중복 시작을 차단한다.
- 최초 확정 `original_text` 변경은 PostgreSQL trigger가 차단한다. `display_text` 교정은 revision 검사와 이력 추가를 같은 트랜잭션에서 처리한다.
- H2 빠른 테스트용 migration은 PostgreSQL trigger/부분 인덱스를 대체하지 않는다. 해당 제약은 실제 PostgreSQL을 사용하는 통합 테스트에서 검증한다.

## Flyway 순서
1. `V1__create_meetings_and_topics.sql`
2. `V2__create_recordings_and_stream_sessions.sql`
3. `V3__create_transcript_speakers_and_revisions.sql`
4. `V4__complete_local_recording_storage.sql` — 단일 회의별 녹음, 청크 순번/해시/파일 메타데이터, 녹음 최종화.
5. `V5__create_offline_glossary_and_review.sql` — 공유 용어·별칭·주제 연결, 검토 교정 제안, 근거/revision snapshot을 보존하는 버전형 가상 요약.
6. `V6__create_app_settings.sql` — 단일 사용자 예산·경고 기준·유료 작업 시간·보관 검토 주기와 revision.
7. `V7__create_ai_usage_ledger.sql` — 공급자 독립 비용 원장. 예산 변경과 원장 갱신은 설정 행 잠금으로 직렬화한다. API 사용량 집계와 완료 녹음 집계는 각각 요청 예약 월·회의 시작 월(UTC)을 사용한다. 실제 공급자 호출은 아직 연결하지 않는다.
8. `V8__guard_active_deepgram_sessions.sql` — 회의별 활성 POC 세션과 비용 요청의 연결을 보존한다.
9. `V9__create_owner_authentication.sql` — 단일 소유자 비밀번호 해시와 영속 세션 해시·만료 시간을 보관한다.
10. `V10__support_topic_ai_usage.sql` — AI 비용 원장의 주체를 회의 또는 주제 중 정확히 하나로 제한하고, OpenAI 용어 후보 출처를 허용한다. 주제가 삭제돼도 월 비용 기록은 유지한다.

## 보관 정책
녹음, 임시 chunk, 로그, 전사/요약, 백업 각각의 보존·삭제 정책을 분리한다. 기본 보존기간은 사용자 설정 이전에는 강제 자동삭제하지 않으며, UI에서 용량과 삭제 대상을 알려준다.
