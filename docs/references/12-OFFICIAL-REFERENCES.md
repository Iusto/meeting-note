# 12. 공식 문서 출처 / 검증 로그

> 최초 확인: 2026-10-15. 추가 확인한 항목은 표에 2026-10-17로 표시했다. API 사용 직전 지원범위·요금·파라미터를 **재확인**한다. 아래 링크는 구현 근거이며 회의 품질 실측이 아니다.

2026-10-21 재확인: 아래 모델·언어·diarization·keyterm·가격, OpenAI 모델·Responses·Structured Outputs 페이지에서 기존 계약을 확인했다. `gpt-6.1-sol`, `reasoning.effort:low`, `service_tier:default`를 유지한다. 공개 지원 정보는 계정별 접근 권한·한국어 정확도·실청구 검증을 대신하지 않는다.

| 항목 | 공식 링크 | 확인 사항 |
|---|---|---|
| Deepgram 모델·언어 | https://developers.deepgram.com/docs/models-languages-overview | Nova-3가 한국어(`ko`, `ko-KR`) 및 회의/스트리밍 지원 |
| Deepgram 화자 분리 | https://developers.deepgram.com/docs/diarization | `diarize_model=latest`: streaming은 현재 v1, prerecorded 현재 v2; v2를 streaming에 쓰면 오류 |
| Deepgram Keyterm | https://developers.deepgram.com/docs/keyterm | Nova-3에서 최대 100개 용어, 반복 `keyterm` 파라미터 |
| Deepgram streaming | https://developers.deepgram.com/docs/live-streaming-audio | WS 스트림, interim/final, 오디오 인코딩 및 연결 관리 참조 |
| Deepgram Live 계약 | https://developers.deepgram.com/reference/speech-to-text/listen-streaming | 2026-10-17 재확인: 서버 Token 헤더, Results/Metadata, 단어별 시간·화자 |
| Deepgram container 오디오 | https://developers.deepgram.com/docs/determining-your-audio-format-for-live-streaming-audio/ | 2026-10-17 재확인: container 오디오는 encoding/sample_rate 생략 |
| Deepgram 연결 유지·종료 | [KeepAlive](https://developers.deepgram.com/docs/audio-keep-alive), [CloseStream](https://developers.deepgram.com/docs/close-stream) | 2026-10-17 재확인: 텍스트 제어 메시지, 종료 후 남은 결과·Metadata 수신 |
| Deepgram 가격 | https://deepgram.com/pricing | streaming Nova-3 $0.0048/min 프로모션(정상 $0.0077), diarization +$0.002, keyterm +$0.0013 |
| Deepgram 동적 keyterm | https://developers.deepgram.com/changelog/2026/10/12 | SDK에서 mid-stream keyterm 변경 지원. 초기 MVP에는 필요 없음 |
| OpenAI Sol | https://developers.openai.com/api/docs/models/gpt-6.1-sol | `gpt-6.1-sol` Responses 가능, Standard 입력 $2/출력 $10 per 1M tokens |
| OpenAI Responses | https://developers.openai.com/api/reference/resources/responses/methods/create | 2026-10-17 재확인: `store:false`, `service_tier:default`, `reasoning.effort:low`, 출력 한도에 reasoning 포함, 상태·usage 확인 |
| OpenAI Structured Outputs | https://developers.openai.com/api/docs/guides/structured-outputs | 2026-10-17 재확인: `text.format`의 strict JSON Schema, 모든 필드 required·추가 필드 금지, 미상 담당자/일정 null, refusal 별도 처리 |
| OpenAI 입력·출력 | https://developers.openai.com/api/docs/guides/text | 2026-10-17 재확인: instructions와 회의 데이터 분리, reasoning 뒤 assistant output_text 탐색 |
| OpenAI 데이터 사용 | https://developers.openai.com/api/docs/guides/your-data | API 입력의 학습/보관 정책 공식 확인 필요 |
| OpenAI 모델 종료 | https://developers.openai.com/api/docs/deprecations | 종료 예정 모델과 교체 권고 확인 |
| OpenAI 가격 | https://developers.openai.com/api/docs/pricing | Sol 가격/캐시/서비스 티어 등 확인 |
| PostgreSQL 15 잠금 조회 | https://www.postgresql.org/docs/15/view-pg-locks.html | 2026-10-22 확인: bigint advisory key의 classid/objid·objsubid=1, 현재 DB·backend·ExclusiveLock 일치 검사 |
| PostgreSQL 15 관리 함수 | https://www.postgresql.org/docs/15/functions-admin.html | 2026-10-22 확인: session advisory lock 해제와 pg_terminate_backend timeout; 격리 시험 backend만 종료 |
| JDBC Connection·pool | [Connection](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html), [PooledConnection](https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/javax/sql/PooledConnection.html) | network timeout·abort와 물리 연결 종료; pool 논리 close만으로 세션 잠금 해제를 보장하지 않음 |

## 사실/가정 구분
- 인증 구현 근거(2026-10-22): [OWASP 비밀번호 저장](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)의 PBKDF2-HMAC-SHA256 600,000회와 salt, [OWASP 세션 관리](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)의 난수 세션·쿠키 속성·서버 만료/로그아웃을 확인했다. 새 의존성 없이 JDK를 사용한다. 30일 유지는 개인 소유 기기의 편의를 위한 명시적 선택이며 보편적인 세션 시간 권고로 간주하지 않는다.
- **공식 확인:** Nova-3 한국어 언어코드, diarization 기능, keyterm 가능, 공개 가격, Sol 모델 ID/요금.
- **미검증:** 실제 한국어 MES 정확도, 동일인 화자 유지, 겹친 발언 완벽 분리, P95 3초, 외부 STT의 4시간 연속 안정성, 개인 월 청구액.
- **기록:** POC 측정 후 실험일·입력 환경·버전·오류 유형·실제 청구 내역을 별도 실험 결과로 추가.
