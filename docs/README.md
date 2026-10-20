# 문서 안내

한국어 AI 회의록의 설계·구현·검증 문서다. 처음에는 [프로젝트 소개](../README.md), [개발 계획](08-IMPLEMENTATION-PLAN.md), [검증 현황](07-TEST-AND-EVAL.md)을 읽는다.

현재 앱은 **MOCK / LOCAL_RECORDING**이다. Deepgram·OpenAI 통신 코드는 로컬 가짜 서버로 검증했으며 실제 공급자 연결·한국어 품질·과금 검증은 남아 있다.

## 제품과 설계

| 문서 | 내용 |
|---|---|
| [01 요구사항](01-PRD.md) | 목적, 기능 우선순위, 수용 기준과 제외 범위 |
| [02 사용자 흐름](02-USER-FLOW.md) | 목표 화면, 발언 상태, 예외 상황 |
| [03 아키텍처](03-ARCHITECTURE.md) | 브라우저·API·저장소·외부 AI의 경계 |
| [04 데이터 모델](04-DATA-MODEL.md) | 테이블, 원문 불변, revision, Flyway |
| [05 API 계약](05-API-CONTRACT.md) | 구현된 REST·이벤트와 향후 relay 계약 |
| [06 AI 파이프라인](06-AI-PIPELINE.md) | 전사·화자·용어 교정·근거 기반 요약 정책 |

## 개발과 운영

| 문서 | 내용 |
|---|---|
| [07 테스트·검증](07-TEST-AND-EVAL.md) | 최신 검증 결과, 재현 경로, 품질·스트레스 기준 |
| [08 개발 계획](08-IMPLEMENTATION-PLAN.md) | Phase 0~4 진행 상태, 남은 작업, 미확정 질문·NO-GO |
| [09 비용](09-COST.md) | 확인 당시 단가, 사용량 시나리오와 예산 정책 |
| [10 기술 결정](10-DECISIONS.md) | 채택·제안·보류·제외한 선택과 근거 |
| [13 보안·운영](13-SECURITY-OPS.md) | 인증, 비밀 관리, 보관·삭제·백업, VPN/HTTPS 조건 |

실행 명령은 [프로젝트 README](../README.md), 브라우저 시험은 [E2E 안내](../tests/e2e/README.md), 백업·복원은 [운영 스크립트 안내](../scripts/README.md)에 둔다.

## 참고자료

- [공식 API 출처](references/12-OFFICIAL-REFERENCES.md): 지원 기능·파라미터·가격의 확인 근거. 실제 호출 전에 재확인한다.
- [설계 논의 이력](../HISTORY.md), [변경 이력](../CHANGELOG.md): 결정 배경과 변경 기록.

## 문서 관리

작업 상태는 08, 최신 실행 결과와 한계는 07, 기술 결정은 10에 갱신한다. 같은 테스트 숫자나 작업 일지를 여러 문서에 반복 추가하지 않는다. 외부 출처는 `references/`에 두고, 빈 실험 양식이나 작업별 완료 문서는 추가하지 않는다. 핵심 문서 번호는 기존 참조를 위해 유지한다.
