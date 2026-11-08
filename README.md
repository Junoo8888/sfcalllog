# 세포 콜로그 (sfcalllog)

갤럭시에서 통화가 끝나면 → "세일즈포스에 기록할까요?" 팝업 → 리드 선택 → 세포에 Call Task 자동 생성.

## 흐름

1. 통화 종료 (발신 전부 + 받은 수신. 안 받은 수신은 무시)
2. 화면 아래 팝업: `X` / `이 번호 안 묻기` / `기록하기`
   - 팝업에 이미 "번호로 찾은 리드"가 표시됨
3. `기록하기` → 상세 화면
   - 번호로 찾은 리드/연락처가 1건이면 자동 선택
   - 없으면 회사·브랜드·담당자 이름 입력 → 비슷한 이름까지 찾아서 목록 표시
     (예: "아임 웹", "(주)아임웹", "아이엠웹" → 아임웹)
4. 통화 결과 버튼(부재중 / 타겟 아님, 퀄리파잉 탈락 …) 또는 직접 메모
5. `세포에 등록` → Task 생성

## 세포에 들어가는 값 (기존 Log a Call 형식 그대로)

| 필드 | 값 |
|---|---|
| Subject | Call |
| TaskSubtype / Type | Call |
| Status | 완료 |
| ActivityDate | 통화한 날짜 |
| Description | 메모 |
| WhoId | 리드 또는 연락처 |
| WhatId | (연락처·계정 선택 시) 진행 중 영업기회, 없으면 계정 |
| CallType / CallDurationInSeconds | 발신·수신 / 통화 시간 |

## 세일즈포스 Connected App 설정 (1회)

설정 → 앱 관리자 → 해당 Connected App → 편집
- 콜백 URL에 `sfcalllog://oauth/callback` 추가
- OAuth 범위: `api`, `refresh_token, offline_access`
- PKCE 사용 가능. "Require Secret for Web Server Flow"가 켜져 있으면 앱에 Consumer Secret도 입력
- 관리(Manage) → 정책 편집: 리프레시 토큰 정책 = "취소할 때까지 유효"

## 빌드

`main`에 올리면 GitHub Actions가 APK를 만들고 Releases의 `latest`에 `sfcalllog.apk`로 올림.
서명키(`app/debug.keystore`)가 고정이라 새 버전은 지우지 않고 덮어 설치 가능.
