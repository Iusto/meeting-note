# 06. AI 파이프라인 · 전문용어 교정 · 화자 정책

아래 정책은 Mock과 실제 공급자 경로에 함께 적용한다. LIVE 전사는 Deepgram, 교정·요약·용어 후보는 OpenAI로 연결되며 실제 호출은 서버의 명시적 유료 opt-in과 예산이 모두 있을 때만 시작한다. 구현 상태는 [08](08-IMPLEMENTATION-PLAN.md), 내부 계약은 [05](05-API-CONTRACT.md), 공급자 계약의 근거는 [공식 출처](references/12-OFFICIAL-REFERENCES.md)를 따른다.

## Provider 역할
| 단계 | 현재 계획 | 확정/검증 |
|---|---|---|
| Live STT | Deepgram `nova-3`, 한국어 `ko`, interim/final, diarization, keyterm | 웹앱 연결 대상, 실제 품질 미검증 |
| 배치 STT/재전사 | Deepgram Nova-3 prerecorded 또는 동등한 API | 필요 구간만, P1 이후 |
| 전문용어 후보 | OpenAI Responses `gpt-6.1-sol` | 사용자가 승인해야 적용 |
| 문맥 교정 | `gpt-6.1-sol` + 규칙 기반 필터 | 확정 전사 기반, 비동기 |
| 최종 요약 | `gpt-6.1-sol` Structured Outputs | 출처 발언 ID 의무 |

## 실시간 경로
`Audio input → Nova-3 partial → UI 표시 → final+speaker timestamp → DB → 안전한 로컬 규칙 → 필요한 경우 Sol 패치 → UI 부분 갱신`.
- 화자 라벨은 **실시간에서도 불완전**할 수 있다. Deepgram 스트리밍은 `diarize_model=latest`(현재 v1), 배치가 현재 v2임을 혼동하지 않는다.
- `keyterm`으로 MES/BOM/LOT/WIP 등을 보내되 비용 추가·최대 100개·프로바이더 현재 규격 검증.
- provider confidence가 있어도 **실제 정답률로 오해 금지**. 로또/LOT 등 언어 맥락을 검토한다.

## 주제 사전 생성 플로우
1. 새로운 주제(MES) 등록.
2. Sol이 `canonical`, `meaning`, `aliases`, `exampleSentences`, `topic` 후보 JSON 생성.
3. 사용자가 후보 승인 후 DB에 저장(승인 전 keyterm 전송 금지).
4. 회의 시작 시 주제별 상위 용어를 keyterm으로 전송(최대 100개/비용 확인).
5. 사용자의 수동 수정은 다음 용어 후보로만 반영(자동 학습/자동 치환 아님).

## 교정 규칙
- 원문과 1:1 연결된 **패치만** 반환. API가 전체 전사본을 새로 출력하게 하지 않는다.
- `로떠`, `로뜨`, `로또`는 MES 문맥에서 `LOT` 가능성이 있으나 복권 이야기면 **로또** 그대로 유지.
- 숫자·LOT 식별번호·기한·금액·담당자·고유명사에 **근거 없는 수정 금지**.
- `금요일... 아니 목요일`의 정정은 의미 보존; 더듬음 제거는 가독성 뷰로 한정.
- LLM이 `confidence: 0.99`라고 하더라도 확률 보정이 아닌 모형의 자가 평정이다.

### LLM 교정용 schema 예시

설계 의도를 설명하는 예시이며 endpoint payload를 보장하지 않는다. 실제 검증 규칙은 `provider/openai/MeetingAiPayloads.java`를 기준으로 한다. 긴 전사는 여러 요청으로 나눠 처리하고, 교정·요약·용어 후보는 사용자가 버튼을 눌렀을 때만 생성한다. 저장된 교정과 `PENDING / OPENAI` 용어 후보는 사용자가 검토·승인한다.

```json
{
  "edits": [
    {
      "segmentId": "<existing-segment-id>",
      "originalSpan": "로떠 번호",
      "replacement": "LOT 번호",
      "reason": "MES/생산 추적 문맥",
      "requiresReview": false
    }
  ],
  "reviewRequired": [
    {"segmentId":"<existing-segment-id>", "reason":"LOT 식별번호 발음 불명확"}
  ]
}
```
- 서버가 segmentId의 소유 회의 확인, `originalSpan` 일치 확인, 숫자/식별번호 패치 차단, 중복/겹치는 위치 변경 확인 후 반영.
- Structured Outputs schema를 강제하고 임의 JSON 파싱만으로 신뢰하지 않는다.

## 요약 schema (예시)
```json
{
  "summary": "...",
  "decisions": [{"text":"...", "sourceSegmentIds":["..."]}],
  "actionItems": [{"text":"...", "assignee":null, "dueDate":null, "sourceSegmentIds":["..."]}],
  "unresolved": [{"question":"...", "sourceSegmentIds":["..."]}]
}
```
- 긴 회의는 chunk/window별 내용 정리 → 전체 통합 요약. 각 요약은 근거 ID를 이어받는다. 모델의 긴 컨텍스트 지원이 문맥 누락을 없애지는 않는다.

## 비용 보호
- 회의 중 모든 부분자막을 Sol에 보내지 않는다: **최종 발언**만 디바운스/그룹화하여 보낸다.
- 미검증 재전사 남발 금지. 중요 고유번호는 자동 확정보다는 사용자 재청취 제공.
- 신규 주제 후보는 1회 생성 후 캐시·재사용.
