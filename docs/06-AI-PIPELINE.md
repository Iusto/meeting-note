# 06. AI 파이프라인 · 전문용어 교정 · 화자 정책

기본 운영은 실시간 마이크와 녹음 업로드를 로컬 Whisper로 전사한다. 교정·요약·용어 후보에는 ChatGPT 계정의 Codex 구독 한도를 쓴다. [사용 순서](02-USER-FLOW.md) · [API 계약](05-API-CONTRACT.md)

## 처리 단계

| 단계 | 현재 동작 |
|---|---|
| 마이크 전사 | Whisper large-v3가 한국어 음성을 약 8초씩 처리한다. 확정 텍스트와 발언 시각을 저장하고 화자는 미정으로 표시한다. |
| 파일 전사 | FFmpeg가 업로드 음성을 PCM16으로 변환하고 Whisper가 최대 30초씩 처리한다. 전사를 저장하면 요약을 시작한다. |
| 전문용어 후보·문맥 교정 | `gpt-6.2-sol`이 주제별 용어 후보와 확정 전사에 대한 교정 제안을 만든다. 사용자 검토 후 적용한다. |
| 회의 요약 | `gpt-6.2-sol`이 발언 ID가 포함된 구조화 결과를 반환한다. 진행률은 구간·통합·저장 단계 완료를 기준으로 계산한다. |

## 주제 사전 생성 플로우
1. 새로운 주제(MES) 등록.
2. Codex가 표준어(`canonical`), 뜻(`meaning`), 별칭(`aliases`) 후보를 만들면 승인 대기 상태로 저장한다.
3. 검토한 후보를 승인하거나 용어를 수동으로 등록하고, 새 회의나 녹음 업로드에서 해당 주제를 선택한다.
4. 기본 Whisper 모드에서 사전은 전사 후 교정 요청에 사용된다. 별도 Deepgram 모드는 시작 시 승인 표준어를 keyterm으로 전달한다.
5. 요약은 저장된 표시 문장을 입력으로 사용한다. 사전 교정을 승인한 뒤 요약을 새로 생성하면 바뀐 문장을 사용한다. 업로드는 전사 후 바로 첫 요약을 생성한다.

## 교정 규칙
- 원문과 1:1 연결된 **패치만** 반환. API가 전체 전사본을 새로 출력하게 하지 않는다.
- `로떠`, `로뜨`, `로또`는 MES 문맥에서 `LOT` 가능성이 있으나 복권 이야기면 **로또** 그대로 유지.
- 숫자·LOT 식별번호·기한·금액·담당자·고유명사에 **근거 없는 수정 금지**.
- `금요일... 아니 목요일`의 정정은 의미 보존; 더듬음 제거는 가독성 뷰로 한정.
- LLM이 `confidence: 0.99`라고 하더라도 확률 보정이 아닌 모형의 자가 평정이다.

### LLM 교정용 schema 예시

설계 의도를 설명하는 예시이며 endpoint payload를 보장하지 않는다. 실제 검증 규칙은 `provider/openai/MeetingAiPayloads.java`를 기준으로 한다. 긴 전사는 여러 요청으로 나눠 처리한다. 마이크 회의의 요약·교정과 용어 후보는 사용자가 요청하고, 업로드 회의는 전사가 끝나면 요약을 자동으로 생성한다. 저장된 교정과 `PENDING / CODEX` 용어 후보는 사용자가 검토·승인한다. 별도 OpenAI API 모드의 후보 출처는 `OPENAI`다.

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
- 요약·교정 요청은 저장된 확정 발언을 사용한다. 업로드 회의의 자동 요약은 전사 저장 후 시작한다.
- 미검증 재전사 남발 금지. 중요 고유번호는 자동 확정보다는 사용자 재청취 제공.
- 주제 용어 후보는 사용자가 생성 요청할 때 저장하고, 검토·승인 후 해당 주제의 교정에 사용한다.
