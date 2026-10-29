# AGENTS.md — Codex CLI 프로젝트 지침 (v2.0)

## 목표
개인 실사용 + 백엔드 개발 연습용 한국어 AI 회의록을 **작은 단계로 검증·구현**한다. `README.md`, `docs/01-PRD.md`, `docs/03-ARCHITECTURE.md`, `docs/08-IMPLEMENTATION-PLAN.md`를 읽고 시작한다. `HISTORY.md`는 의사결정 역사로만 읽는다.

## 변경 불가 규칙
1. **개인용 범위 유지:** 유료화·과금 청구·멀티테넌트·조직 권한·Kubernetes·Kafka·Redis·벡터 DB·로컬 LLM 서비스를 선제 도입하지 않는다.
2. **웹앱 통합 검증:** 2026-10-25 사용자 요청(ADR-021)에 따라 실제 AI를 웹앱에 연결하고 같은 경로에서 품질·비용을 확인한다. 별도 유료 POC를 필수 선행하지 않으며, 연결 준비·기능 동작·품질 실측의 완료를 구분한다.
3. **API 비용 기본 차단:** Mock 모드가 기본. 실비 발생 테스트는 명시적 opt-in 설정(`PAID_API_TESTS_ENABLED`)과 최대 시간/금액을 요구한다.
4. **비밀은 서버만:** Deepgram/OpenAI의 장기 API 키를 브라우저로 전달하거나 Git/DB/로그에 저장하지 않는다. 브라우저와 공급자 사이 WebSocket은 서버 중개를 기본으로 한다.
5. **원본 유지:** 원본 녹음, 최초 확정 STT 원문을 수정하지 않는다. AI 교정·사용자 수정은 revision으로 기록한다.
6. **모르는 것은 모른다고 표시:** 숫자·LOT 식별번호·날짜·담당자·화자명은 근거 없이 생성·변경하지 않는다. LLM 신뢰도는 정답 확률로 취급하지 않는다.
7. **UI는 늦게라도 계속 표시:** 실시간 임시 텍스트→확정 원문→비동기 교정. LLM 대기나 화자 재분석이 입력 오디오 전송/녹음/자막 표시를 막지 않는다.
8. **녹음과 전사는 별도 경로:** 외부 STT 장애에도 가능한 동안 로컬 녹음을 보존한다. 복구·동기화와 멱등 업로드를 설계한다.
9. **코드의 단순성:** 자연스러운 변수명·함수명, 이해 가능한 모듈 경계, 불필요한 추상화 금지. Flyway migration + JUnit/Vitest 테스트를 포함한다.
10. **실측 없는 성능 주장 금지:** P95 3초, 4시간 무손실은 **목표**, 검증 전 달성치가 아니다.
11. **민감 회의 보호:** 실제 회사 회의는 녹음·외부 전송 승인 및 내부 규정 확인 전 업로드 금지. 테스트 데이터는 가상 합성 사례만 사용한다.
12. **선택·가정 분리:** `docs/10-DECISIONS.md`의 ACCEPTED/PROPOSED/DEFERRED를 존중하고 결정 변경 시 ADR과 `HISTORY.md`에 날짜·근거를 남긴다.

## 예상 모듈 (Phase를 통과할 때만 생성)
```text
apps/web/                # React SPA
apps/api/                # Spring Boot API / WebSocket relay
docs/                    # 요구사항·아키텍처·실험
examples/                # 민감정보 없는 목 데이터
infra/                   # Docker Compose, 설정
```
Python Worker/pyannote, 로컬 Whisper, Redis, 유료 구독 로직은 **현재 구현 대상이 아니다**.

## 공급자 구현 유의사항
- Deepgram Live Listen 스트림은 서버 WebSocket relay에서 처리하고 `Interim/Final`·화자·단어 timestamp·오류·세션 교체를 **공식 계약에 맞게** 매핑한다.
- Deepgram 최신 diarization: 스트리밍은 `diarize_model=latest`(현재 v1), 배치는 `latest`(현재 v2). `diarize=true`와 동시 사용하지 않는다. **공식 레퍼런스 재확인 후 실제 코드화**.
- Keyterm은 `keyterm` 파라미터를 반복하며, 최대 100개. 개별 용어 승인이 필수.
- GPT-6.1 Sol은 Responses API Structured Outputs를 사용하고 모든 교정은 segment ID와 증거 구간을 참조하도록 한다.
- 숫자와 고유번호는 불일치 시 확인 대상에 남기고 자동 덮어쓰지 않는다.

## 완료 보고 형식
문서는 [docs/README.md](docs/README.md)에서 탐색한다. 작업 상태는 08, 최신 검증 결과는 07, 기술 결정은 10에 갱신하고 같은 실행 일지나 빈 실험 양식을 별도 문서로 추가하지 않는다.

1. 변경 파일과 기능
2. 실행·테스트 명령 및 통과/실패 결과
3. 유료 API 호출 여부/추정비용(없으면 0)
4. 남은 위험, 다음 Phase 1개

## 작업 재개
`docs/08-IMPLEMENTATION-PLAN.md`의 현재 상태와 다음 작업부터 진행한다. Phase 0과 실제 AI 소스 연결은 완료했으며, 단계별 테스트를 통과한 뒤 다음 범위를 구현한다. 실제 유료 호출은 사용자가 웹앱의 월 예산·회의 상한 안에서 실사용으로 검증하며 에이전트는 임의로 실행하지 않는다.
