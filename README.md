# 유튜브 요약

유튜브 앱에서 **공유 → 유튜브 요약**을 누르면 영상의 **대본·요약·지시문**을 만들어 휴대폰과 이 저장소(`data` 브랜치)에 보관하는 개인용 안드로이드 앱. 비밀번호 없이 열린다.

## 처리 방식
| 버튼 | 동작 |
|---|---|
| 대본 | 휴대폰에서 유튜브 자막을 바로 추출. 자막이 없으면 GitHub Actions에서 Gemini가 영상을 듣고 받아쓰기 |
| 요약 | GitHub Actions → Gemini 요약 |
| 지시문 | GitHub Actions → Gemini가 "~하려면 → 단계"만 추출 (감탄·연결어·배경설명 제거) |
| 바로가기 | 유튜브 앱으로 해당 영상 열기 |

결과 위치: `data` 브랜치 `videos/{영상ID}/meta.json, transcript.md, summary.md, instructions.md`

## 처음 한 번 설정
1. **Gemini 키 등록** – 저장소 Settings → Secrets and variables → Actions → New repository secret
   - 이름 `GEMINI_API_KEYS`, 값에 키를 한 줄에 하나씩 여러 개 (또는 `GEMINI_API_KEY_1` ~ `GEMINI_API_KEY_10`)
   - 한도 초과·오류 키는 자동으로 다음 키로 넘어가고, 그날 다 쓴 키는 `state/gemini.json`에 기록돼 건너뜀
   - (선택) Variables에 `GEMINI_MODELS` = `gemini-flash-latest,gemini-2.5-flash` 처럼 모델 순서 지정
2. **워크플로를 기본 브랜치(main)에 반영** – `.github/workflows/process.yml`이 main에 있어야 앱이 실행할 수 있음
3. **앱 설정에 GitHub 토큰 입력(1회)** – Fine-grained token, 이 저장소만 선택, 권한 `Contents: Read and write`, `Actions: Read and write`

## 빌드
```
./gradlew assembleDebug
```
서명 키는 `signing/`(git 제외)에 두며 저장소에 올리지 않는다.
