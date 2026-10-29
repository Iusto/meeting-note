# 13. 개인정보·비밀·홈서버 운영

운영 대상은 단일 소유자의 Mock/로컬 녹음/LIVE AI 앱이다. 유료 AI는 서버 운영자가 명시적으로 켠 경우에만 동작한다. 실행 스크립트의 전체 옵션은 [운영 도구 안내](../scripts/README.md), 검증 결과는 [07](07-TEST-AND-EVAL.md)에 둔다.

## 평소 시작·종료·업데이트

서버 PC 전원과 Docker Desktop의 Linux 엔진, Tailscale 연결이 필요하다. 다른 기기도 같은 tailnet에 로그인해야 한다. PC가 꺼지거나 절전하면 접속과 녹음이 중단될 수 있다.

| 상황 | 저장소 루트에서 할 일 |
|---|---|
| 최초 비공개 배포·설정 점검 | [Windows 배포 스크립트](../scripts/README.md#windows-private-deployment) 실행 |
| PC 재시작 후 | Docker·Tailscale 실행 뒤 `docker compose -f compose.yaml -f compose.tailscale.yaml up -d`, `tailscale serve status` 확인 |
| 코드 변경 반영 | 같은 Compose 명령에 `--build` 추가. 녹음 중에는 실행하지 않음 |
| 상태 확인 | `docker compose ps`, 같은 tailnet 기기에서 HTTPS 주소·로그인·녹음 재생 확인 |
| 종료 | 진행 중 녹음을 종료·업로드 확인한 뒤 같은 Compose 파일 조합으로 `down` |

Compose의 `restart: unless-stopped`는 Docker 엔진이 시작돼야 동작한다. `down -v`는 DB·녹음 볼륨을 삭제하므로 평소 종료에 사용하지 않는다. 기존 `.env`를 덮어쓰거나 새 토큰을 불필요하게 생성하지 않는다. 배포 스크립트는 기본적으로 유료 호출을 끄고, `-EnablePaidAi`를 명시한 실행에서만 서버 프로세스에 키를 전달한다.

## 원칙
- 회의 녹음 사실과 외부 STT(Deepgram), 교정/요약(OpenAI) 전송 여부를 명확하게 안내. 참석자의 동의와 소속 조직 정책을 확인한다.
- 회사 MES/ERP/IT 회의는 영업비밀, 개인 정보, 설비번호 등을 포함할 수 있으므로 **업무 목적 녹음·제3자 API 전송이 허용되는지** 확인한 후 사용한다.
- 개인 사용이라도 Deepgram/OpenAI 데이터 보관·처리 지역·서비스 약관 확인. 암호화·삭제 정책 마련.

## 키/네트워크
- 최초 계정 등록 때만 `APP_OWNER_TOKEN`을 확인한다. 등록 후에는 아이디·비밀번호를 사용하고 토큰 로그인은 닫는다. 비밀번호는 PBKDF2 해시, DB 세션은 무작위 쿠키의 SHA-256 해시만 보관한다. 공용 기기에서는 30일 로그인 유지를 선택하지 않고 사용 후 로그아웃한다.
- `.env`/환경변수의 `DEEPGRAM_API_KEY`, `OPENAI_API_KEY`는 서버만 접근하고 브라우저에 보내지 않는다.
- Spring Boot 서버가 Deepgram WS relay를 담당하고, 브라우저는 인증된 우리 WSS로 PCM16 음성을 보낸다. 공급자 키는 브라우저·응답·로그·DB에 전달하지 않는다.
- WebSocket Origin/세션 검증, 요청 크기·레이트 제한, 연결 종료 처리. HTTPS/WSS 사용.
- 집 PC는 Tailscale 같은 VPN 우선, 공유기 공개 포트 개방은 기본 금지. PostgreSQL은 localhost/internal Docker network에만 바인딩.
- 현재 녹음 청크 API는 서버 생성 경로·경로 탈출/링크 거부·SHA-256·청크 8MiB 제한·MIME 일치를 검증한다. 임의 파일 업로드·형식 sniff·디스크 전체 용량 제한은 별도 구현/검증이 필요하다.

## 녹음 보관
- DB에는 파일 경로·hash만, 실제 음성은 안전한 로컬 경로.
- 주기적으로 디스크 잔량/오류 확인. 청크 업로드는 멱등·재개 가능하도록 설계.
- DB 백업과 오디오 백업을 **함께** 수행하며 복원 테스트. 삭제 요청 시 백업 보관 정책 명시.
- 청크는 IndexedDB에 보관한 뒤 업로드하고 서버 완료 응답 후 지운다. 실패한 청크는 같은 브라우저에서 복구한다. 탭 종료는 녹음을 중단하며 브라우저 데이터 삭제·시크릿 창 종료는 미업로드 청크를 지울 수 있다. 중단된 녹음에 새 MediaRecorder 녹음을 이어붙이지 않는다.

## 로그·비용
- 로깅: requestId, meetingId, streamSessionId, 처리 시간, provider·모델·오디오분·토큰, 오류 코드. 로그인 쿠키·인증 세션 원문은 기록하지 않는다.
- 금지: 음성 본문, 전사 원문, 장기 키, 전체 인증 헤더, 개인정보가 담긴 프롬프트.
- 유료 호출은 기본 OFF다. LIVE 실사용은 월·회의 시간/금액 상한 안에서 실행하고 재연결·OpenAI 재요청을 누적한다. 월 예산 기본값 0을 사용자가 양수로 바꾸기 전에는 호출을 시작하지 않는다. [비용 정책](09-COST.md)을 따른다.
- STT 공급자 가격·요금제 변경, 장애·API 스펙 변경을 운영 문서에 기록.

## 인증
초기 로컬 테스트 단일 계정이어도 네트워크 공개 시 인증·HTTPS 필수. 사용자 1명이므로 OAuth/멀티테넌시 설계는 불필요하다.

## 오프라인 보관·삭제·백업 운영

- 보관 검토 주기의 기본값은 제한 없음이다. 기간을 정해도 자동으로 삭제하지 않는다. 사용량 화면에서 오래된 녹음을 검토하고 회의 제목을 입력해 명시적으로 삭제한다. 진행 중인 회의와 오래된 revision의 삭제는 거절한다.
- 삭제 범위는 해당 회의의 서버 녹음·청크·전사·수정 이력·요약과 현재 브라우저의 복구용 IndexedDB 데이터다. 다른 브라우저, 다운로드, 백업 사본은 별도 보관 정책으로 관리한다.
- 비용 원장은 예산 우회를 막기 위해 회의 삭제 후에도 유지한다. 회의 UUID와 비용·모델·사용량만 남기며 제목·전사·음성·API 키는 저장하지 않는다.
- 파일을 먼저 격리하고 DB 삭제를 커밋한다. DB 실패 시 복구하며, 커밋 후 파일 정리가 실패하면 `cleanupPending`을 알리고 다음 서버 시작에 정리를 재시도한다. 예상하지 않은 파일·링크가 있으면 삭제를 중단한다. 파일 시스템의 안전 검사가 실패한 상태를 임의 재귀 삭제로 해결하지 않는다.
- 백업/복구 명령과 합성 데이터 테스트는 [운영 스크립트 안내](../scripts/README.md)에 있다. API를 완전히 중지하고 DB와 녹음의 한 묶음을 백업한다. 복구는 기존 DB/녹음 디렉터리를 덮어쓰지 않고 새 DB와 새 디렉터리를 사용한다. 복구 검증 후에만 앱의 DB·녹음 경로를 전환한다.
- 백업의 해시는 손상 감지 수단이다. 자신이 만든 신뢰 가능한 DB dump만 복원한다. 백업에는 전사·원본 음성·과거 파일 경로와 소유자 비밀번호/세션의 해시도 포함되며 암호화하지 않는다. 복원 시 계정은 유지하고 세션은 모두 폐기해 다시 로그인하도록 한다. 사용자 전용 저장 위치와 별도 백업 보존기간을 사용하고 Git에 추가하지 않는다.

## VPN/HTTPS 배포 게이트

기본 Compose는 localhost HTTP이며 전달 헤더를 신뢰하지 않는다. 선택 설정 [compose.tailscale.yaml](../compose.tailscale.yaml)은 `Tailscale Serve → 127.0.0.1:8088 → Nginx → 내부 API` 경로를 위한 것이다. Nginx는 외부 전달 헤더를 제거하고 고정 HTTPS 호스트·443 포트를 전달하며, 이 모드에서만 API가 전달 헤더를 신뢰한다. API·DB 포트를 호스트에 추가 공개하지 않는다.

Docker와 두 기기의 Tailscale 로그인 후, MagicDNS·HTTPS를 활성화하고 `.env`의 `TAILSCALE_HOST`에 **프로토콜·경로 없는 정확한 호스트명**을 지정한다. HTTPS 인증서 발급 시 그 호스트명은 공개 인증서 기록에 남는다. [공식 HTTPS 안내](https://tailscale.com/docs/how-to/set-up-https-certificates)

```powershell
docker compose -f compose.yaml -f compose.tailscale.yaml up -d --build
tailscale serve --bg http://127.0.0.1:8088
tailscale serve status
```

`WEB_PORT`를 바꿨다면 Serve 대상 포트도 바꾼다. 같은 tailnet의 다른 기기에서 표시된 HTTPS 주소로 접속한다. Serve는 tailnet 전용이며 공개 Funnel이나 공유기 포트 개방은 사용하지 않는다. [공식 Serve 안내](https://tailscale.com/docs/features/tailscale-serve)

Windows에서는 [배포 스크립트](../scripts/README.md#windows-private-deployment)로 주소 감지·비밀 생성·Compose 실행·인증 응답 확인·Serve 설정을 진행할 수 있다. 기존 `.env`와 Docker 데이터를 유지한다. 기본 실행은 유료 호출을 끄며 `-EnablePaidAi` 실행만 Windows 사용자/현재 프로세스의 공급자 키를 사용한다.

기존 배포의 화면 접속·로그인·녹음 재생과 프록시 시험 결과는 [07](07-TEST-AND-EVAL.md)에 기록했다. 변경 이미지에서는 다른 기기의 로그인·마이크 권한·인증된 WSS·`Secure; HttpOnly; SameSite=Strict` 쿠키·허용하지 않은 Origin 거절을 실제 주소로 다시 확인한다. 실제 공급자 WSS는 별도 확인 대상이다. 전달 헤더 설정의 근거는 [Spring Boot 3.5 공식 문서](https://docs.spring.io/spring-boot/3.5/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server)다.
