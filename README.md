# AI Meeting

집 PC에서 운영하는 개인용 한국어 AI 회의록 프로젝트다. React·TypeScript와 Java 21·Spring Boot, PostgreSQL을 사용하며 녹음은 디스크에 저장한다.

목표는 실시간 전사·화자 구분·MES/ERP/IT 용어 교정과 근거 기반 회의 요약이다. 현재는 **MOCK / LOCAL_RECORDING**으로 동작한다. 실제 STT·LLM은 앱에 연결하지 않았으며 유료 API 호출은 차단한다.

## 현재 기능

| 기능 | 동작 |
|---|---|
| 회의 관리 | 로그인, 생성·조회·제목 수정·검색·삭제 |
| 마이크 녹음 | 장치·동의 확인, 브라우저 청크 보관, 재시도 업로드, 재생·다운로드 |
| 사전과 전사 검토 | 주제·용어·별칭 승인, 교정 후보 검토, 원문·수정 이력 보존 |
| 요약과 내보내기 | 근거 발언을 인용하는 가상 요약, Markdown·JSON 다운로드 |
| 사용량·보관 | 완료 녹음 집계, 비용 예약·예산 차단 준비, 청구 미확인 구분, 보관·삭제·백업/복원 |

Deepgram Nova-3는 한국어 실시간 STT의 POC 후보이며 OpenAI `gpt-6.1-sol` Responses는 교정·요약용으로 선정했다. 가짜 공급자로 전사 저장·교정/요약 저장·비용 원장 연결을 검증하는 준비 코드를 추가했다. 운영 앱의 마이크 전송·AI 활성화는 실제 POC 통과 후 연결한다. 합성 마이크 4시간의 녹음 저장·업로드 복구·해시·재생을 검증했으며 실제 한국어 정확도·화자·지연·비용과 외부 STT 장시간 연결은 검증 전이다. SaaS·결제·조직 계정·로컬 AI 서비스는 범위 밖이다.

## 실행

Docker Desktop의 Linux 엔진과 Compose가 필요하다. 2026-10-19 사용자 실행 로그에서 이미지 빌드·컨테이너 기동을 확인했고, 배포 후 로컬 HTTP·Tailscale HTTPS 화면 응답과 미인증 API의 401 응답을 직접 확인했다.

```powershell
Copy-Item .env.example .env
# .env의 APP_OWNER_TOKEN(무작위 32자 이상), POSTGRES_PASSWORD 변경
docker compose up --build
```

`http://localhost:8088`에 처음 접속하면 기존 `APP_OWNER_TOKEN`으로 소유자를 확인하고 아이디·비밀번호를 등록한다. 이후에는 아이디·비밀번호로 로그인하며, 로그인 유지를 선택하면 서버 재시작 후에도 최대 30일 유지된다. 공용 기기에서는 해제한다. 공개 회원가입은 제공하지 않는다. Mock 회의에서는 합성 전사를, `마이크 녹음 · 음성 인식 미연결` 회의에서는 실제 마이크 저장 흐름을 확인한다. 현재 공급자 API 키는 필요하지 않으며 `PAID_API_TESTS_ENABLED=true`도 앱 시작을 거부한다.

웹만 `127.0.0.1`에 바인딩하며 API·DB는 내부 네트워크를 쓴다. 종료는 `docker compose down`을 사용한다. `down -v`는 DB·녹음 볼륨을 삭제한다. 다른 기기에서의 마이크 사용은 [Tailscale HTTPS 설정·검증](docs/13-SECURITY-OPS.md#vpnhttps-배포-게이트)을 따른다. 배포 PC의 HTTPS 응답과 사용자 휴대폰의 로그인·녹음 파일 재생을 확인했다. 실제 STT의 WSS 연결은 미검증이다.

마이크는 시작 버튼을 누른 뒤 켠다. 청크는 IndexedDB에 먼저 저장하고 SHA-256과 함께 업로드하며 실패한 자료는 같은 브라우저에서 `보관한 청크 복구`로 재전송한다. 탭을 닫으면 녹음이 중단되고, 브라우저 데이터 삭제·시크릿 창 종료는 미업로드 자료를 지울 수 있다. 중단된 녹음에 새 녹음을 이어붙이지 않는다. 합성 마이크 4시간 시험은 통과했으나 실제 디스크 포화·브라우저 강제 종료·절전과 실제 마이크의 음질은 추가 검증 대상이다.

## 로컬 개발과 테스트

JDK 21, Node.js 22.12 이상(또는 20.19 이상), PostgreSQL이 필요하다. Bash에서는 `./mvnw`를 사용한다.

```powershell
cd apps/api
$env:APP_OWNER_TOKEN='<무작위 32자 이상의 로컬 로그인 토큰>'
$env:DATABASE_URL='jdbc:postgresql://localhost:5432/ai_meeting'
$env:DATABASE_USER='ai_meeting'
$env:DATABASE_PASSWORD='<로컬 DB 비밀번호>'
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

별도 터미널에서:

```powershell
cd apps/web
npm ci
npm test
npm run lint
npm run build
npm run dev
```

웹은 `http://localhost:5173`, API health는 `http://localhost:8080/actuator/health`다. Vite가 `/api`와 WebSocket을 중계한다. API 포트가 다르면 웹 실행 전에 `API_PROXY_TARGET`을 지정하고 API의 `APP_ALLOWED_ORIGINS`에도 실제 웹 주소를 지정한다.

기본 JUnit은 H2를 사용한다. 실제 PostgreSQL 검증은 유지관리 DB 연결과 `CREATEDB` 권한으로 실행한다. 테스트는 `ai_meeting_test_<임의 ID>` DB를 생성하고 연결 풀 종료 후 삭제하며 지정한 DB의 회의 테이블에 자료를 쓰지 않는다.

```powershell
$env:APP_TEST_DATABASE_URL='jdbc:postgresql://localhost:5432/postgres'
$env:APP_TEST_DATABASE_USER='ai_meeting'
$env:APP_TEST_DATABASE_PASSWORD='<테스트 DB 비밀번호>'
.\mvnw.cmd test
```

브라우저 시험에는 별도 테스트 앱·DB를 사용한다. [E2E 실행 안내](tests/e2e/README.md), [백업·복원과 기본 과금 없는 POC 사전 점검](scripts/README.md), [검증 결과와 한계](docs/07-TEST-AND-EVAL.md)를 참고한다.

## 문서

- [문서 목록](docs/README.md): 제품·설계, 개발·운영, 공식 출처
- [개발 계획](docs/08-IMPLEMENTATION-PLAN.md): Phase 0~4 현재 상태와 다음 작업
- [테스트·검증](docs/07-TEST-AND-EVAL.md): 최근 실행 결과·품질 기준·미검증 범위
- [기술 결정](docs/10-DECISIONS.md): 선택과 보류 근거
- [작업 지침](AGENTS.md): 개발 에이전트가 지킬 규칙

작업 시 `AGENTS.md`와 개발 계획을 먼저 읽는다. 문서 우선순위는 작업 지침 → 최신 기술 결정·요구사항 → 관련 설계다. [HISTORY.md](HISTORY.md)는 당시 논의의 배경 자료다.
