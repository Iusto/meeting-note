# 13. 개인정보·비밀·홈서버 운영

## 원칙
- 회의 녹음 사실과 외부 STT(Deepgram), 교정/요약(OpenAI) 전송 여부를 명확하게 안내. 참석자의 동의와 소속 조직 정책을 확인한다.
- 회사 MES/ERP/IT 회의는 영업비밀, 개인 정보, 설비번호 등을 포함할 수 있으므로 **업무 목적 녹음·제3자 API 전송이 허용되는지** 확인한 후 사용한다.
- 개인 사용이라도 Deepgram/OpenAI 데이터 보관·처리 지역·서비스 약관 확인. 암호화·삭제 정책 마련.

## 키/네트워크
- `.env`/환경변수의 `DEEPGRAM_API_KEY`, `OPENAI_API_KEY`는 서버만 접근하고 브라우저에 보내지 않는다.
- Spring Boot 서버가 Deepgram WS relay를 담당하고, 브라우저는 인증된 우리 WS로만 전송.
- WebSocket Origin/세션 검증, 요청 크기·레이트 제한, 연결 종료 처리. HTTPS/WSS 사용.
- 집 PC는 Tailscale 같은 VPN 우선, 공유기 공개 포트 개방은 기본 금지. PostgreSQL은 localhost/internal Docker network에만 바인딩.
- 파일 업로드는 format sniff, 이름 재생성, 경로 탈출 방지, 파일별 크기 및 총 저장량 제한.

## 녹음 보관
- DB에는 파일 경로·hash만, 실제 음성은 안전한 로컬 경로.
- 주기적으로 디스크 잔량/오류 확인. 청크 업로드는 멱등·재개 가능하도록 설계.
- DB 백업과 오디오 백업을 **함께** 수행하며 복원 테스트. 삭제 요청 시 백업 보관 정책 명시.
- API 또는 네트워크 장애 시 녹음 유지 가능 여부 확인, 실제 브라우저 종료 시 보존 한계 UI 안내.

## 로그·비용
- 로깅: requestId, meetingId, sessionId, 처리 시간, provider·모델·오디오분·토큰, 오류 코드.
- 금지: 음성 본문, 전사 원문, 장기 키, 전체 인증 헤더, 개인정보가 담긴 프롬프트.
- 개발 실비 호출은 기본 OFF, 테스트당 시간 제한, 월 예산 상한 및 초과 차단 로직 추가.
- STT 공급자 가격·요금제 변경, 장애·API 스펙 변경을 운영 문서에 기록.

## 인증
초기 로컬 테스트 단일 계정이어도 네트워크 공개 시 인증·HTTPS 필수. 사용자 1명이므로 OAuth/멀티테넌시 설계는 불필요하다.

## 오프라인 보관·삭제·백업 운영

- 보관 검토 주기의 기본값은 제한 없음이다. 기간을 정해도 자동으로 삭제하지 않는다. 사용량 화면에서 오래된 녹음을 검토하고 회의 제목을 입력해 명시적으로 삭제한다. 진행 중인 회의와 오래된 revision의 삭제는 거절한다.
- 삭제 범위는 해당 회의의 서버 녹음·청크·전사·수정 이력·요약과 현재 브라우저의 복구용 IndexedDB 데이터다. 다른 브라우저, 다운로드, 백업 사본은 별도 보관 정책으로 관리한다.
- 비용 원장은 예산 우회를 막기 위해 회의 삭제 후에도 유지한다. 회의 UUID와 비용·모델·사용량만 남기며 제목·전사·음성·API 키는 저장하지 않는다.
- 파일을 먼저 격리하고 DB 삭제를 커밋한다. DB 실패 시 복구하며, 커밋 후 파일 정리가 실패하면 `cleanupPending`을 알리고 다음 서버 시작에 정리를 재시도한다. 예상하지 않은 파일·링크가 있으면 삭제를 중단한다. 파일 시스템의 안전 검사가 실패한 상태를 임의 재귀 삭제로 해결하지 않는다.
- 백업/복구 명령과 합성 데이터 테스트는 [운영 스크립트 안내](../scripts/README.md)에 있다. API를 완전히 중지하고 DB와 녹음의 한 묶음을 백업한다. 복구는 기존 DB/녹음 디렉터리를 덮어쓰지 않고 새 DB와 새 디렉터리를 사용한다. 복구 검증 후에만 앱의 DB·녹음 경로를 전환한다.
- 백업의 해시는 손상 감지 수단이다. 자신이 만든 신뢰 가능한 DB dump만 복원한다. 백업에는 전사·원본 음성과 과거 파일 경로가 포함되며 암호화하지 않는다. 사용자 전용 저장 위치와 별도 백업 보존기간을 사용하고 Git에 추가하지 않는다.

## VPN/HTTPS 배포 게이트

기본 Compose는 localhost HTTP이며 전달 헤더를 신뢰하지 않는다. 선택 설정 [compose.tailscale.yaml](../compose.tailscale.yaml)은 `Tailscale Serve → 127.0.0.1:8088 → Nginx → 내부 API` 경로를 위한 것이다. Nginx는 외부 전달 헤더를 제거하고 고정 HTTPS 호스트·443 포트를 전달하며, 이 모드에서만 API가 전달 헤더를 신뢰한다. API·DB 포트를 호스트에 추가 공개하지 않는다.

Docker와 두 기기의 Tailscale 로그인 후, MagicDNS·HTTPS를 활성화하고 `.env`의 `TAILSCALE_HOST`에 **프로토콜·경로 없는 정확한 호스트명**을 지정한다. HTTPS 인증서 발급 시 그 호스트명은 공개 인증서 기록에 남는다. [공식 HTTPS 안내](https://tailscale.com/docs/how-to/set-up-https-certificates)

```powershell
docker compose -f compose.yaml -f compose.tailscale.yaml up -d --build
tailscale serve --bg http://127.0.0.1:8088
tailscale serve status
```

`WEB_PORT`를 바꿨다면 Serve 대상 포트도 바꾼다. 같은 tailnet의 다른 기기에서 표시된 HTTPS 주소로 접속한다. Serve는 tailnet 전용이며 공개 Funnel이나 공유기 포트 개방은 사용하지 않는다. [공식 Serve 안내](https://tailscale.com/docs/features/tailscale-serve)

Windows에서는 [배포 스크립트](../scripts/README.md#windows-private-deployment)로 주소 감지·비밀 생성·Compose 실행·인증 응답 확인·Serve 설정을 진행할 수 있다. 유료 호출은 강제로 끄며 실제 배포와 구분한 가짜 명령 테스트 7개를 통과했다.

설정 검사와 프록시 쿠키·Origin 통합 시험은 통과했다. 2026-10-19 사용자 로그에서 이미지 빌드·DB/API/web Healthy를 확인했고, Serve 활성화 후 배포 PC에서 로컬 HTTP·Tailscale HTTPS 화면 200과 미인증 API 401을 직접 확인했다. HTTPS는 표준 인증서 검증을 사용했다. 에이전트의 제어 named pipe 제한은 유지된다. 다른 기기에서 로그인·마이크 권한·인증된 WSS·`Secure; HttpOnly; SameSite=Strict` 쿠키·허용하지 않은 Origin 거절을 실제 주소로 확인하는 작업은 남아 있다. 전달 헤더 설정의 근거는 [Spring Boot 3.5 공식 문서](https://docs.spring.io/spring-boot/3.5/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server)다.
