# AI Meeting

집 PC에서 운영하는 개인용 한국어 회의록 웹앱이다. 회의를 녹음하고, 전문용어를 검토하며, 발언을 근거로 회의 내용을 정리한다.

앱은 `MOCK`, `LOCAL_RECORDING`, `LIVE` 모드를 제공한다. `LIVE`는 같은 마이크 입력을 원본 녹음과 Deepgram Nova-3 실시간 전사에 나눈다. 교정·요약·주제별 용어 후보는 Windows Codex CLI의 ChatGPT 로그인으로 `gpt-6.1-sol`을 실행한다. 기본 배포는 요약에 OpenAI API 키를 사용하지 않으며 Codex 구독 한도가 적용된다. Deepgram 전사에는 별도 API 비용이 발생한다.

## 주요 기능

| 영역 | 현재 동작 |
|---|---|
| 소유자 로그인 | 최초 토큰 확인으로 계정 등록, 이후 아이디·비밀번호 로그인, 선택적 30일 로그인 유지 |
| 회의 관리 | 생성·조회·제목 수정·검색·삭제, Markdown·JSON 내보내기 |
| 마이크 녹음 | 장치 선택, 브라우저 청크 보관, 실패한 업로드 복구, 재생·다운로드 |
| 용어 사전·검토 | 주제별 용어·별칭 승인, Codex 후보·교정 제안 검토, 원문과 수정 이력 보존 |
| 요약·회의 화면 | 근거 발언을 참조하는 버전형 요약, 긴 전사 분할 처리, 내부 스크롤 전사·고정 요약 사이드바 |
| 사용량·보관 | Codex 계정 잔여율·갱신 시간 조회, 전사 API 비용 예약·월/회의 상한, 이전 비용 이력 보존, DB·녹음 백업/복원 |

2026-11-04 운영 Docker 배포, 실제 Codex 합성 요약 생성과 구독 한도 조회를 확인했다. 자동 회귀 테스트는 가짜 공급자를 사용한다. 실제 장시간 회의의 요약 품질·한국어 인식률·화자 구분·지연·실청구액은 별도 검증 대상이다. 자세한 상태는 [개발 계획](docs/08-IMPLEMENTATION-PLAN.md), 증거와 한계는 [테스트·검증](docs/07-TEST-AND-EVAL.md)에 둔다.

## 구조와 설계

```mermaid
flowchart LR
  Browser[React 웹앱] -->|REST · 인증된 WebSocket| API[Spring Boot]
  Browser -->|녹음 청크 업로드| API
  API --> DB[(PostgreSQL)]
  API --> Audio[(녹음 파일)]
  API -->|서버 전용 키 · PCM16 음성| DG[Deepgram Nova-3]
  API -->|토큰 인증 · 구조화 요청| Bridge[Windows Codex bridge]
  Bridge -->|ChatGPT 로그인| CLI[Codex CLI]
  CLI --> AI[OpenAI 모델 서버]
```

- **녹음과 전사를 분리한다.** 외부 AI가 중단돼도 로컬 녹음과 업로드 복구 경로를 유지한다.
- **원본을 보존한다.** 최초 확정 전사는 수정하지 않고 교정과 사용자 편집을 revision으로 남긴다.
- **근거를 확인한다.** 요약은 발언 ID를 참조하고, 숫자·LOT·날짜·담당자를 근거 없이 바꾸지 않는다.
- **개인 운영에 맞춘다.** 단일 소유자·동시 회의 1개, PostgreSQL과 파일 저장을 사용한다. SaaS·결제·조직 계정·로컬 AI 운영은 범위 밖이다.

| 계층 | 기술 |
|---|---|
| 웹 | React 18, TypeScript, Vite, Tailwind CSS |
| 서버 | Java 21, Spring Boot 3.5, REST, WebSocket |
| 저장 | PostgreSQL 17, Flyway, 로컬 녹음 파일 |
| 운영 | Docker Compose, Tailscale Serve의 비공개 HTTPS |
| 테스트 | JUnit, H2·실제 PostgreSQL, Vitest, Playwright, Python unittest |

경계와 처리 흐름은 [아키텍처](docs/03-ARCHITECTURE.md), 선택 이유와 변경은 [기술 결정](docs/10-DECISIONS.md)에 설명한다.

## 실행

Docker Desktop의 Linux 엔진과 Compose가 필요하다. 저장소 루트에서 실행한다.

```powershell
if (-not (Test-Path -LiteralPath .env)) { Copy-Item .env.example .env }
# 최초 설정: .env의 APP_OWNER_TOKEN(무작위 32자 이상), POSTGRES_PASSWORD 변경
docker compose up -d --build
```

이미 `.env`가 있으면 복사 단계를 생략한다. `http://localhost:8088`에서 최초 한 번 `APP_OWNER_TOKEN`으로 소유자를 확인하고 계정을 등록한다. 이후에는 아이디·비밀번호를 사용한다. Mock 회의는 합성 전사를, 마이크 녹음 회의는 실제 녹음 저장을 확인하는 용도다.

기본 실행은 공급자 호출을 끈다. 비공개 배포에서 실제 AI를 사용할 때는 Windows 사용자 또는 현재 프로세스에 `DEEPGRAM_API_KEY`를 등록하고, Node.js·Codex CLI 설치 및 `codex login`으로 ChatGPT 로그인을 완료한 뒤 다음 스크립트를 실행한다. CLI 창을 열어둘 필요는 없다. 스크립트가 숨겨진 bridge를 시작하고 요약 요청 시 CLI를 호출한다. 키를 `.env`에 기록하거나 출력하지 않으며, `-EnablePaidAi`가 없으면 공급자 호출을 끈다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-private.ps1 -EnablePaidAi
```

전사 API 월 예산 기본값은 `0 USD`이므로 로그인 후 **사용량 → 전사 API 예산·녹음 보관 설정**에서 양수로 저장해야 LIVE 전사가 시작된다. 기본 시간 상한은 회의당 최대 4시간이며 회의당 금액 초기값은 3 USD다. Codex 요약은 달러 예산을 예약·차감하지 않는다. 전사 비용은 설정 단가를 사용한 사전 예약·추정치이며 공급자의 최종 청구액을 보장하지 않는다.

종료는 `docker compose down`이다. `down -v`는 DB·녹음 볼륨을 삭제한다. 웹은 기본 `127.0.0.1:8088`에만 바인딩하고 API·DB는 내부 네트워크에서 사용한다. 다른 기기에서는 양쪽 Tailscale에 로그인한 뒤 HTTPS 주소로 접속한다. 설정과 PC 재시작 후 운영 방법은 [보안·운영](docs/13-SECURITY-OPS.md#vpnhttps-배포-게이트)을 따른다.

## 로컬 개발과 테스트

JDK 21, Node.js 22.12 이상(또는 20.19 이상), PostgreSQL이 필요하다.

```powershell
cd apps/api
$env:APP_OWNER_TOKEN='<무작위 32자 이상의 로컬 토큰>'
$env:DATABASE_URL='jdbc:postgresql://localhost:5432/ai_meeting'
$env:DATABASE_USER='ai_meeting'
$env:DATABASE_PASSWORD='<로컬 DB 비밀번호>'
.\mvnw.cmd spring-boot:run
```

별도 터미널에서 `apps/web`으로 이동해 `npm ci`, `npm run dev`를 실행한다. 웹은 `http://localhost:5173`, API health는 `http://localhost:8080/actuator/health`다. Vite가 API와 WebSocket을 중계한다. 포트를 바꾸면 웹의 `API_PROXY_TARGET`과 서버의 `APP_ALLOWED_ORIGINS`를 함께 설정한다.

기본 검증은 `apps/api`의 `.\mvnw.cmd test`, `apps/web`의 `npm test`, `npm run lint`, `npm run build`다. Bash에서는 `./mvnw`를 사용한다. 실제 PostgreSQL·브라우저·운영 검증의 조건과 명령은 [테스트 안내](docs/07-TEST-AND-EVAL.md#테스트-실행)에 모았다. 기본 테스트는 유료 API를 호출하지 않는다.

## 문서

| 목적 | 문서 |
|---|---|
| 제품과 설계 이해 | [요구사항](docs/01-PRD.md) · [사용자 흐름](docs/02-USER-FLOW.md) · [아키텍처](docs/03-ARCHITECTURE.md) |
| 구현 상세 | [데이터 모델](docs/04-DATA-MODEL.md) · [API 계약](docs/05-API-CONTRACT.md) · [AI 파이프라인](docs/06-AI-PIPELINE.md) |
| 현재 상태 확인 | [테스트·검증](docs/07-TEST-AND-EVAL.md) · [개발 계획](docs/08-IMPLEMENTATION-PLAN.md) |
| 비용과 운영 | [비용 정책](docs/09-COST.md) · [기술 결정](docs/10-DECISIONS.md) · [보안·운영](docs/13-SECURITY-OPS.md) |

[문서 전체 안내](docs/README.md) · [운영 스크립트](scripts/README.md) · [브라우저 테스트](tests/e2e/README.md) · [개발 지침](AGENTS.md)

### 연결 복구와 이어 녹음

AI 연결 오류는 녹음 저장과 별개다. 월 예산 등을 수정한 뒤 녹음 화면의 **AI 전사 다시 연결**을 누르면 새 음성부터 전사한다. 새로고침으로 중단된 미종료 회의는 같은 브라우저에서 **청크 복구 후 녹음 재개**로 이어간다. 중단된 동안의 음성은 복구되지 않는다. 월 예산과 회의당 최대 예산은 **사용량·예산**에서 각각 저장한다. 이어 녹음의 최종 WebM 병합에는 FFmpeg가 필요하며 Docker 이미지에 포함된다.
## Codex 구독 요약 연결 (2026-11-04)

`scripts/start-private.ps1 -EnablePaidAi`는 Deepgram 전사와 Windows Codex CLI 요약을 연결합니다. Node.js와 Codex CLI를 설치하고 `codex login`으로 ChatGPT에 로그인해야 합니다. 요약 버튼을 누르면 Docker API → 이 PC의 인증된 bridge → `codex exec` → 요약 저장 순서로 실행됩니다. 모델은 OpenAI 서버에서 실행되며 구독 한도가 적용됩니다. 이 PC와 bridge가 켜져 있어야 합니다.

기본 실행은 `OPENAI_API_KEY`를 컨테이너에 전달하지 않으며 요약·교정·용어 후보에 API 달러 예산을 예약하지 않습니다. Deepgram 전사는 계속 별도 유료 API를 사용하므로 `DEEPGRAM_API_KEY`와 전사 예산은 필요합니다. 이전 API 비용 기록은 보존합니다. 기존 OpenAI API 방식은 `-UseOpenAiApi`를 추가할 때만 선택합니다. 자세한 실행 방법은 [운영 스크립트 안내](scripts/README.md)를 참고하세요.
녹음 중에도 회의 화면의 **화자 이름 설정**에서 감지된 화자에게 이름·역할을 지정할 수 있습니다. 이름은 회의별로 저장되며 같은 화자의 발언에 바로 표시됩니다. 입력을 비워 저장하면 기본 이름으로 돌아갑니다. 재연결 후 새 화자가 생기면 별도로 지정하세요.

새 AI 요약은 **핵심 요약 → 주제별 논의 → 확정 결정 → 할 일 → 확인할 사항**으로 구성됩니다. 근거를 펼치면 발언 시각·화자·내용을 확인할 수 있습니다. 이미 저장된 요약은 보존되며 새 형식 적용에는 요약 재생성이 필요합니다.
