# 10. ADR / 현재 기술 결정 (2026-10-15)

상태: **ACCEPTED** 현재 채택, **PROPOSED** 측정/POC 전제, **DEFERRED** 의도적 보류, **REJECTED** 현재 제외.

| ID | 결정 | 상태 | 이유 |
|---|---|---|---|
| ADR-001 | 개인 실사용·백엔드 학습을 프로젝트 유일 1차 목표 | ACCEPTED | 유료화는 당시 가설, 현재 범위 제외 |
| ADR-002 | React + TS + Vite + Tailwind | ACCEPTED | 지속 갱신되는 실시간 UI, SSR 불필요 |
| ADR-003 | Java 21 + Spring Boot 모듈러 모놀리식 | ACCEPTED | 업무로직, 비동기, WS relay, 개인 개발 경험 |
| ADR-004 | PostgreSQL + Flyway, 음성은 파일 저장 | ACCEPTED | 간단한 관계형 모델·버전관리·복원 |
| ADR-005 | 외부 클라우드 STT 기본; 로컬 AI 운영하지 않음 | ACCEPTED | RTX 3060 Ti 8GB는 실시간 AI 다중 추론 병목 가능, 유지비/복잡성 |
| ADR-006 | Deepgram Nova-3 한국어 스트리밍 + keyterm + 화자 구분 | PROPOSED | 기능·단가 매력적이나 한국어 MES 정확도 **미측정**, Phase1 POC 필수 |
| ADR-007 | OpenAI `gpt-6.1-sol` Responses 교정/요약 | ACCEPTED | 품질-비용 균형; 서버 키 보관, 토큰/단가 검증 |
| ADR-008 | WebSocket STT 서버 relay | ACCEPTED | 클라이언트에 공급자 장기 키 비노출, 시간축/재연결 통제 |
| ADR-009 | Deepgram 스트리밍 화자 라벨 기본, 오차 시 사용자 수정 | PROPOSED | v1 스트리밍 diarizer 실제 오류 미측정 |
| ADR-010 | 실시간 자막을 먼저, GPT 교정을 나중에 | ACCEPTED | 사용자 체감속도/비동기 병목 분리 |
| ADR-011 | 전문용어 AI 추천→승인→keyterm+선택적 교정 | ACCEPTED | 변형 표현 교정 및 사용자 승인 안전장치 |
| ADR-012 | 원본 음성/STT 불변, 고유번호 자동 변경 금지 | ACCEPTED | 과신·오교정 방지 |
| ADR-013 | 월 36~48시간 예상, 60시간 스트레스; 비용 가드 | ACCEPTED | 개인 비용 민감성, 고비용 API 보호 |
| ADR-014 | Python pyannote Worker | REJECTED for MVP | 별도 GPU/파이프라인 유지 부담; Deepgram 화자 기능 먼저 검증 |
| ADR-015 | `gpt-4o-transcribe-diarize` 장기 의존 | REJECTED | 종료 예정 모델 회피 |
| ADR-016 | 결제·구독·팀/조직·멀티테넌시·SaaS 기능 | REJECTED for scope | 개인 사용 우선 |
| ADR-017 | Redis/Kafka/K8s/벡터 DB/로컬 LLM·자동 AI Router | DEFERRED | 실측 필요 전에는 복잡성 증가만 야기 |
| ADR-018 | 실시간 STT POC를 파일 MVP보다 우선 | ACCEPTED | 가장 높은 제품 위험이 실시간 화자별 자막 품질 |
| ADR-019 | API 키 준비 전 공급자 독립 기능을 mock/로컬 녹음으로 선행 개발 | ACCEPTED (2026-10-16) | 사용자 요청. Phase 1 실측·채택 판단은 미완료로 유지하고, 독립 부분만 단계별 테스트 후 진행 |

## 의사결정 변경 절차
POC 결과를 먼저 `docs/07-TEST-AND-EVAL.md` 표 형식으로 기록하고, 기존 ADR 상태/결정 날짜/근거를 갱신한다. 기능보다 **실측 정확도·지연·사용료**가 우선이다.
