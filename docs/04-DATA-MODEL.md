# 04. PostgreSQL 데이터 설계 (단일 사용자)

## 원칙
- 서버는 사용자 한 명만을 대상으로 설계. **조직, 멤버십, 결제, 구독, 팀 권한 테이블 없음**.
- MVP는 로컬/VPN 환경 최소 인증으로 시작하되 외부 인터넷 공개 시 인증 필수.
- UUID PK, `timestamptz`, 오디오 위치는 `start_ms/end_ms` 정수, `schema_version` 기록.
- 음성 BLOB은 DB에 저장하지 않는다. 원본·최초 STT 불변, 수정 이력은 추가만 한다.

| 테이블 | 필드(핵심) | 제약/역할 |
|---|---|---|
| `meeting_topics` | id, name, description, created_at | `name` unique |
| `glossary_terms` | id, canonical, meaning, status, created_at | 표준어 unique/승인 상태 |
| `glossary_aliases` | id, term_id, alias, normalized_alias, approved_at | `(term_id, normalized_alias)` unique |
| `topic_terms` | topic_id, term_id, priority | compound PK |
| `glossary_suggestions` | id, topic_id, candidate_json, state, source_meeting_id | AI 추천→승인 수동 |
| `meetings` | id, title, topic_id, input_source, status, started_at, ended_at, revision | 최근 회의 인덱스 |
| `stream_sessions` | id, meeting_id, provider, provider_session_id, audio_offset_ms, started_at, ended_at, close_reason | 재연결/비용 단위 |
| `recordings` | id, meeting_id, state, path, sha256, bytes, duration_ms | 오디오 파일은 로컬에 저장 |
| `recording_chunks` | id, recording_id, sequence_no, start_ms, end_ms, sha256, state | `(recording_id, sequence_no)` unique |
| `speakers` | id, meeting_id, provider_label, display_name, review_state | `(meeting_id, provider_label)` unique |
| `transcript_segments` | id, meeting_id, provider_event_id, start_ms, end_ms, original_text, display_text, transcript_status, speaker_id nullable, speaker_status, revision, user_locked | `(meeting_id, provider_event_id)` unique, time 인덱스 |
| `transcript_revisions` | id, segment_id, before_text, after_text, actor, reason, created_at | 원문 불변, 교정 패치 감사 |
| `correction_suggestions` | id, segment_id, suggested_edits_json, reason, review_state | pending/applied/rejected |
| `meeting_summaries` | id, meeting_id, version, content_json, model, state | 결과 변경 버전 |
| `action_items` | id, meeting_id, description, assignee_text nullable, due_date nullable, source_segment_ids, review_state | 누락 값은 nullable |
| `decisions` | id, meeting_id, description, source_segment_ids, review_state | 원문 근거 |
| `processing_jobs` | id, meeting_id, kind, status, attempts, lease_until, error_code | bounded retry |
| `ai_usage_records` | id(요청 멱등 키), meeting_id, provider, model, operation, price_version, state, reservation_month, reserved_usd, estimated_usd, actual_usd nullable, actual_source, maximum_audio_seconds, measured_audio_seconds, input_tokens, output_tokens, created_at, updated_at | 요청별 비용 예약·정산·예산 차단. 회의 UUID는 삭제 후에도 보존하며 FK/cascade를 두지 않음 |

## 데이터 정합성 규칙
- 한 회의에서 `stream_sessions`가 여러 개일 수 있다(재연결). 이벤트의 기준 시간은 **회의 시작부터 누적된 audio offset**.
- `PARTIAL`은 DB에 항상 저장할 필요가 없음. `FINAL` 이벤트만 durable 기록하며 음성 chunk는 별도로 저장.
- 같은 `provider_event_id`를 재전송해도 중복 INSERT가 발생하지 않도록 UPSERT/멱등.
- `speaker_id`는 nullable. overlap은 여러 `transcript_segments`의 시간 범위 중복으로 표현 가능.
- `original_text`는 변경 금지. 사용자 또는 AI 교정은 `display_text` + `transcript_revisions`/proposal로 관리.
- `source_segment_ids`는 존재 여부와 회의 ID 동일성을 검증한다.
- `glossary_terms`는 누적 회의 수와 무관하게 중복 없이 공유되고, 발음 변형도 alias UNIQUE로 관리.

## Phase 0 구현 메모 (2026-10-16)
- V1–V3만 구현한다. 사전·요약·작업·비용 테이블은 후속 Phase에서 추가한다.
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

## 보관 정책
녹음, 임시 chunk, 로그, 전사/요약, 백업 각각의 보존·삭제 정책을 분리한다. 기본 보존기간은 사용자 설정 이전에는 강제 자동삭제하지 않으며, UI에서 용량과 삭제 대상을 알려준다.
