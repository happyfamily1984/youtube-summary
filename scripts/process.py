"""유튜브 영상 처리: 대본 / 요약 / 지시문.

GitHub Actions(process.yml)에서 실행된다. 결과는 data 브랜치 작업 폴더(DATA_DIR)에 쓰고,
커밋·푸시는 워크플로가 한다.

Gemini 키는 GitHub 시크릿에서 읽는다:
  - GEMINI_API_KEYS : 여러 키를 줄바꿈/쉼표로 구분
  - GEMINI_API_KEY, GEMINI_API_KEY_1 ~ GEMINI_API_KEY_10 : 하나씩 등록해도 됨
한도 초과(429)·잘못된 키면 다음 키로 넘어가고, 그날 다 쓴 키는 state/gemini.json에 기록해
다음 실행부터 건너뛴다.
"""
import datetime as dt
import json
import os
import re
import sys
import time
import traceback
import urllib.parse

import requests

DATA = os.environ.get("DATA_DIR", "data")
VIDEO_ID = os.environ["VIDEO_ID"].strip()
IS_YT = len(VIDEO_ID) == 11  # 유튜브 ID는 11자, 인스타·틱톡 등은 접두어 ID
TASK = os.environ["TASK"].strip()
REQUEST_ID = os.environ.get("REQUEST_ID", "").strip()
LANG = (os.environ.get("LANGUAGE") or "ko").strip()
MODELS = [m.strip() for m in (os.environ.get("GEMINI_MODELS") or
          "gemini-flash-latest,gemini-2.5-flash,gemini-flash-lite-latest").split(",") if m.strip()]

VDIR = os.path.join(DATA, "videos", VIDEO_ID)
STATE_PATH = os.path.join(DATA, "state", "gemini.json")
FILES = {"transcript": "transcript.md", "summary": "summary.md",
         "instructions": "instructions.md", "prompt": "prompt.md"}
URL = f"https://www.youtube.com/watch?v={VIDEO_ID}" if IS_YT else (os.environ.get("VIDEO_URL") or "").strip()
# 프롬프트 추출은 화면 분석이 중요해 상위 모델을 먼저 시도
PROMPT_MODELS = [m.strip() for m in (os.environ.get("GEMINI_PROMPT_MODELS") or
                 "gemini-2.5-pro,gemini-flash-latest,gemini-2.5-flash").split(",") if m.strip()]

LANG_NAME = {"ko": "한국어", "en": "English"}.get(LANG, LANG)

PROMPTS = {
    "summary": f"""아래 유튜브 영상 내용을 {LANG_NAME}로 요약하라.

출력 형식(마크다운):
## 한 줄 요약
(한 문장)

## 핵심 내용
- 5~10개 불릿. 수치·고유명사·구체적 예시는 그대로 유지

## 상세 요약
### 주제별 소제목
짧은 문단 또는 불릿

## 결론
- 1~3개 불릿

규칙: "이 영상은~", 인사말, 메타 설명 없이 바로 내용으로 시작한다. 영상에 없는 내용은 추가하지 않는다.""",

    "instructions": f"""아래 유튜브 영상 내용에서 시청자가 실제로 따라 할 수 있는 지시와 방법만 뽑아라.

출력 형식(마크다운):
### ~하려면
1. 단계
2. 단계

규칙:
- 제목은 "~하려면" 형태의 목적, 아래는 번호 단계만.
- 각 단계는 짧은 명령형. 예: "데이터 탭 → 중복된 항목 제거 클릭"
- 감탄사, 연결어, 인사, 배경 설명, 이유·원리 설명, 경험담, 비유, 광고·구독 요청은 모두 뺀다.
- 수치, 설정값, 메뉴 이름, 단축키, 명령어, 재료와 분량, 시간 같은 구체적 값은 반드시 남긴다.
- 주의사항은 단계에 꼭 필요할 때만 "주의: ..." 한 줄로 단계 안에 넣는다.
- 영상에 나온 순서를 따른다.
- 따라 할 지시가 전혀 없으면 "이 영상에는 따라 할 수 있는 지시 내용이 없습니다." 한 줄만 출력한다.
- {LANG_NAME}로 출력한다.""",

    "prompt": f"""너는 영상 연출 분석가이자 AI 영상 생성 프롬프트 엔지니어다. 첨부 영상을 처음부터 끝까지 프레임 단위로 보고,
이 문서만 있으면 같은 영상을 다시 만들 수 있을 만큼 상세한 제작 프롬프트를 작성하라.
(사용자는 나중에 인물과 스토리를 자신의 것으로 바꿔 쓴다. 그러니 구조·연출·타이밍을 빠짐없이 기록하는 것이 목적이다.)

설명은 {LANG_NAME}로, 영상 생성 AI(Veo, Sora, Kling, Runway 등)에 넣을 프롬프트는 영어로 ``` 코드블록 안에 쓴다.

## 1. 영상 개요
- 총 길이, 화면비(9:16, 16:9 등), 추정 해상도·프레임레이트, 포맷(쇼츠 정보형, 브이로그, 광고, 드라마 등), 전체 흐름 3줄

## 2. 비주얼 스타일 바이블
- 촬영 방식(실사/애니/3D/화면녹화 등), 카메라·렌즈 느낌(초점거리, 심도), 색감·색보정(주요 색 HEX 추정), 조명(방향, 세기, 색온도),
  질감(그레인, 선명도), 편집 리듬(평균 컷 길이), 자막·그래픽 스타일(폰트 느낌, 크기, 위치, 색, 등장 애니메이션)
- 위 스타일을 담은 공통 스타일 프롬프트 코드블록 1개

## 3. 등장인물 시트
인물마다 식별자(CHAR_A, CHAR_B …)와 역할, 나이대, 성별 표현, 체형, 피부톤, 얼굴 특징, 헤어(색·길이·스타일),
의상(색·소재·핏·아이템), 액세서리, 자세, 표정 습관, 목소리(톤·속도·억양), 말투.
인물마다 일관성 유지용 영어 묘사 코드블록 1개.

## 4. 장소·소품 시트
장소마다 식별자(LOC_1 …), 공간 구성, 배경 요소, 색, 조명, 시간대, 날씨. 주요 소품 목록.

## 5. 초 단위 타임라인
1초마다 아래 형식을 반복한다. 변화가 적은 초도 생략하지 말고 "이전과 동일 + 미세 변화"로 쓴다.
**[00:00–00:01]** SHOT 번호 · 샷 크기(ECU/CU/MCU/MS/WS/EWS) · 앵글 · 카메라 움직임(방향·속도)
- 화면: 구도(인물 위치, 프레이밍 비율), 동작, 표정, 시선, 손동작, 배경 변화
- 텍스트: 화면 자막·그래픽 원문 그대로 + 위치·색·애니메이션
- 소리: 대사 원문 그대로(화자 표시), 내레이션, 효과음, 음악(분위기, BPM 추정, 바뀌는 시점)
- 전환: 컷/디졸브/줌 트랜지션 등 (있을 때만)

## 6. 샷별 생성 프롬프트
컷이 바뀔 때마다 샷 하나씩. 각 샷:
### SHOT 01 · 00:00–00:03 (3초)
```
[영어] 스타일 + 해당 인물 묘사 전체(다시 포함) + 장소 + 초 단위 동작 순서 + 카메라 움직임 + 조명 + 대사/소리 + 화면 텍스트,
duration 3s, aspect ratio 9:16
```
Negative: 영어로 피해야 할 요소
각 샷 프롬프트는 다른 샷을 참조하지 않고 단독으로 완결되게 쓴다.

## 7. 오디오 트랙
- 대사·내레이션 전체 원문(타임스탬프 포함), 음악 구성(인트로·전환·아웃트로 시점), 효과음 목록(시점 포함)

## 8. 편집 지시
- 컷 목록(시작–끝, 길이), 속도 조절, 자막 타이밍, 줌·흔들림 등 후반 효과

규칙:
- 화면에서 확인한 것만 쓴다. 추정은 "(추정)"이라고 표시한다.
- 초, 비율, 색, 크기 같은 숫자를 최대한 구체적으로 쓴다.
- 인사말이나 머리말 없이 1장부터 바로 시작한다.
- 영상이 3분을 넘으면 5장은 2초 단위로 쓰되, 컷 변화와 대사는 빠짐없이 쓴다.""",

    "transcript": """이 영상의 음성을 원래 언어 그대로 빠짐없이 받아써라.
- 약 30초마다 새 줄을 시작하고 줄 앞에 [mm:ss] 타임스탬프를 붙인다. (1시간 이상이면 [h:mm:ss])
- 요약, 설명, 머리말, 맺음말을 붙이지 않는다. 받아쓴 내용만 출력한다.""",
}


# ---------------- 공통 ----------------

def read(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except FileNotFoundError:
        return None


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def now_iso():
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def quota_day():
    # Gemini 무료 한도는 태평양 시간 자정에 초기화 → UTC-8 기준 날짜
    return (dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=8)).strftime("%Y-%m-%d")


# ---------------- Gemini (키 순환) ----------------

def load_keys():
    keys = []
    raw = os.environ.get("GEMINI_API_KEYS", "")
    keys += [k for k in re.split(r"[\s,;]+", raw) if k]
    for name in ["GEMINI_API_KEY"] + [f"GEMINI_API_KEY_{i}" for i in range(1, 11)]:
        v = (os.environ.get(name) or "").strip()
        if v:
            keys.append(v)
    seen, out = set(), []
    for k in keys:
        if k not in seen:
            seen.add(k)
            out.append(k)
    return out


class Gemini:
    def __init__(self):
        self.keys = load_keys()
        if not self.keys:
            raise RuntimeError("GitHub 시크릿에 GEMINI_API_KEYS(또는 GEMINI_API_KEY_1…)가 없습니다")
        try:
            self.state = json.loads(read(STATE_PATH) or "{}")
        except ValueError:
            self.state = {}
        today = quota_day()
        if self.state.get("today") != today:
            self.state["exhausted"] = {}
        self.state["today"] = today
        self.state.setdefault("exhausted", {})
        self.state["total"] = len(self.keys)
        self.start = int(self.state.get("last_key", 1) or 1)

    def save_state(self):
        self.state["updated_at"] = now_iso()
        write(STATE_PATH, json.dumps(self.state, ensure_ascii=False, indent=2))

    def order(self):
        n = len(self.keys)
        idx = [((self.start - 1 + i) % n) + 1 for i in range(n)]  # 1부터 시작하는 번호
        fresh = [i for i in idx if str(i) not in self.state["exhausted"]]
        tired = [i for i in idx if str(i) in self.state["exhausted"]]
        return fresh + tired  # 다 쓴 키도 마지막에 한 번 더 시도 (한도 초기화됐을 수 있음)

    def generate(self, parts, long_video=False, models=None):
        """parts: 목록 또는 key를 받아 목록을 돌려주는 함수(업로드 파일은 키마다 따로 올려야 함)"""
        gen_cfg = {"temperature": 0.2, "maxOutputTokens": 65536}
        if long_video:
            gen_cfg["mediaResolution"] = "MEDIA_RESOLUTION_LOW"
        errors = []
        for model in (models or MODELS):
            model_missing = False
            for n in self.order():
                key = self.keys[n - 1]
                try:
                    p = parts(key) if callable(parts) else parts
                except Exception as e:
                    errors.append(f"키{n}: 영상 업로드 실패 {e}")
                    continue
                body = {"contents": [{"role": "user", "parts": p}], "generationConfig": gen_cfg}
                for attempt in range(3):
                    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
                    try:
                        r = requests.post(url, json=body, headers={"x-goog-api-key": key}, timeout=600)
                    except requests.RequestException as e:
                        errors.append(f"{model}/키{n}: 네트워크 {e}")
                        time.sleep(5)
                        continue
                    if r.status_code == 200:
                        data = r.json()
                        text = "".join(p.get("text", "") for c in data.get("candidates", [])[:1]
                                       for p in c.get("content", {}).get("parts", []))
                        if not text.strip():
                            reason = (data.get("candidates") or [{}])[0].get("finishReason") or \
                                data.get("promptFeedback", {}).get("blockReason")
                            errors.append(f"{model}/키{n}: 빈 응답 ({reason})")
                            break
                        self.state["last_key"] = n
                        self.state["last_model"] = model
                        self.save_state()
                        print(f"OK model={model} key#{n}")
                        return text.strip()
                    msg = r.text[:300]
                    print(f"{model} key#{n} -> {r.status_code} {msg}", file=sys.stderr)
                    if r.status_code == 429:
                        per_day = "PerDay" in r.text or "per day" in r.text.lower() or "daily" in r.text.lower()
                        if per_day or attempt == 1:
                            self.state["exhausted"][str(n)] = self.state["today"]
                            self.save_state()
                            errors.append(f"키{n}: 한도 초과")
                            break  # 다음 키
                        time.sleep(20)  # 분당 한도 → 잠깐 기다렸다 재시도
                        continue
                    if r.status_code in (400, 401, 403) and ("API_KEY" in r.text or "API key" in r.text
                                                             or r.status_code != 400):
                        errors.append(f"키{n}: 사용 불가 ({r.status_code})")
                        break  # 다음 키
                    if r.status_code == 404:
                        errors.append(f"{model}: 모델 없음")
                        model_missing = True
                        break
                    if r.status_code >= 500:
                        time.sleep(5 * (attempt + 1))
                        continue
                    # 400 등 요청 자체 문제 → 같은 모델로 다른 키를 써도 같음
                    errors.append(f"{model}: {r.status_code} {msg}")
                    model_missing = True
                    break
                if model_missing:
                    break
        raise RuntimeError("Gemini 처리 실패: " + " / ".join(errors[-6:]))


# ---------------- 유튜브 ----------------

def video_meta(info=None):
    path = os.path.join(VDIR, "meta.json")
    meta = None
    try:
        meta = json.loads(read(path) or "null")
    except ValueError:
        pass
    if not meta or not meta.get("title"):
        title, channel = "", ""
        if info:
            title = info.get("title") or (info.get("description") or "")[:80]
            channel = info.get("uploader") or info.get("channel") or ""
        elif IS_YT:
          try:
            r = requests.get("https://www.youtube.com/oembed",
                             params={"format": "json", "url": URL}, timeout=20)
            if r.ok:
                title, channel = r.json().get("title", ""), r.json().get("author_name", "")
          except requests.RequestException:
            pass
        meta = {"id": VIDEO_ID, "title": title, "channel": channel, "url": URL,
                "platform": "YouTube" if IS_YT else (info or {}).get("extractor_key", ""),
                "createdAt": int(time.time() * 1000), **(meta or {})}
        meta["title"] = meta.get("title") or title
        meta["channel"] = meta.get("channel") or channel
        write(path, json.dumps(meta, ensure_ascii=False, indent=2))
    return meta


def fmt_ts(sec):
    s = int(sec)
    return f"{s // 3600}:{s % 3600 // 60:02d}:{s % 60:02d}" if s >= 3600 else f"{s // 60:02d}:{s % 60:02d}"


def captions_transcript():
    """youtube-transcript-api로 자막 받기 (Actions 서버 IP는 막힐 수 있음)"""
    try:
        from youtube_transcript_api import YouTubeTranscriptApi
        api = YouTubeTranscriptApi()
        tl = api.list(VIDEO_ID)
        tr = None
        for finder in (tl.find_manually_created_transcript, tl.find_generated_transcript):
            try:
                tr = finder([LANG, "ko", "en"])
                break
            except Exception:
                pass
        if tr is None:
            tr = next(iter(tl))
        snippets = tr.fetch()
        out, block_start, cur = [], None, []
        for sn in snippets:
            if block_start is None or sn.start - block_start >= 30:
                if cur:
                    out.append(f"[{fmt_ts(block_start)}] " + " ".join(cur))
                block_start, cur = sn.start, []
            cur.append(sn.text.replace("\n", " ").strip())
        if cur:
            out.append(f"[{fmt_ts(block_start)}] " + " ".join(cur))
        text = "\n\n".join(out).strip()
        return text or None
    except Exception as e:
        print(f"자막 API 실패: {e}", file=sys.stderr)
        return None


def video_part(fps=None):
    p = {"file_data": {"file_uri": URL, "mime_type": "video/*"}}
    if fps:
        p["video_metadata"] = {"fps": fps}
    return p


# ---------------- 유튜브 외 영상: 내려받아 Gemini에 업로드 ----------------

def download_video():
    """yt-dlp로 인스타·틱톡 등 영상을 내려받는다. (경로, 정보) 반환"""
    import subprocess
    if not URL:
        raise RuntimeError("영상 주소가 없습니다")
    out = "/tmp/video.%(ext)s"
    cmd = ["yt-dlp", "-q", "--no-playlist", "--no-warnings",
           "-f", "bv*[height<=720][ext=mp4]+ba[ext=m4a]/b[height<=720][ext=mp4]/bv*[height<=720]+ba/b",
           "--merge-output-format", "mp4", "--write-info-json", "-o", out, URL]
    cookies = os.environ.get("YTDLP_COOKIES", "").strip()
    if cookies:
        with open("/tmp/cookies.txt", "w") as f:
            f.write(cookies + "\n")
        cmd[1:1] = ["--cookies", "/tmp/cookies.txt"]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    files = [f for f in os.listdir("/tmp") if f.startswith("video.") and not f.endswith(".json")]
    if r.returncode != 0 or not files:
        hint = " (로그인이 필요한 영상이면 YTDLP_COOKIES 시크릿에 쿠키를 넣으세요)" if "login" in r.stderr.lower() else ""
        raise RuntimeError("영상을 내려받지 못했습니다" + hint + ": " + r.stderr.strip()[-200:])
    info = {}
    try:
        with open("/tmp/video.info.json", encoding="utf-8") as f:
            info = json.load(f)
    except Exception:
        pass
    return "/tmp/" + files[0], info


_uploads = {}


def upload_file(key, path):
    """Gemini Files API 업로드 (키=프로젝트마다 따로). ACTIVE 상태의 file uri 반환"""
    if key in _uploads:
        return _uploads[key]
    size = os.path.getsize(path)
    base = "https://generativelanguage.googleapis.com"
    r = requests.post(f"{base}/upload/v1beta/files", headers={
        "x-goog-api-key": key,
        "X-Goog-Upload-Protocol": "resumable", "X-Goog-Upload-Command": "start",
        "X-Goog-Upload-Header-Content-Length": str(size), "X-Goog-Upload-Header-Content-Type": "video/mp4",
        "Content-Type": "application/json"}, json={"file": {"display_name": VIDEO_ID}}, timeout=60)
    r.raise_for_status()
    up = r.headers["X-Goog-Upload-URL"]
    with open(path, "rb") as f:
        r = requests.post(up, headers={"X-Goog-Upload-Command": "upload, finalize", "X-Goog-Upload-Offset": "0",
                                       "Content-Length": str(size)}, data=f, timeout=600)
    r.raise_for_status()
    file = r.json()["file"]
    for _ in range(60):  # 영상 처리 대기 (최대 5분)
        if file.get("state") == "ACTIVE":
            break
        if file.get("state") == "FAILED":
            raise RuntimeError("Gemini가 영상을 처리하지 못했습니다")
        time.sleep(5)
        file = requests.get(f"{base}/v1beta/{file['name']}", headers={"x-goog-api-key": key}, timeout=30).json()
    _uploads[key] = file["uri"]
    return file["uri"]


# ---------------- 실행 ----------------

def main():
    if TASK not in FILES:
        raise RuntimeError(f"알 수 없는 작업: {TASK}")
    info, local_video, duration = None, None, None
    if not IS_YT:
        local_video, info = download_video()
        duration = info.get("duration")
    meta = video_meta(info)
    title = meta.get("title") or VIDEO_ID
    out_path = os.path.join(VDIR, FILES[TASK])
    tpath = os.path.join(VDIR, "transcript.md")
    transcript = read(tpath)

    def video(fps=None):
        """유튜브는 주소 그대로, 그 외는 업로드한 파일 (키마다 업로드)"""
        if IS_YT:
            return lambda key: [video_part(fps)]
        def f(key):
            p = {"file_data": {"file_uri": upload_file(key, local_video), "mime_type": "video/mp4"}}
            if fps:
                p["video_metadata"] = {"fps": fps}
            return [p]
        return f

    def with_text(vfn, text):
        return lambda key: vfn(key) + [{"text": text}]

    if TASK == "transcript":
        text = captions_transcript() if IS_YT else None
        if not text:
            text = Gemini().generate(with_text(video(), PROMPTS["transcript"]), long_video=IS_YT)
        write(out_path, text + "\n")
    elif TASK == "prompt":
        if not transcript and IS_YT:
            transcript = captions_transcript()
            if transcript:
                write(tpath, transcript + "\n")
        text = PROMPTS["prompt"] + f"\n\n영상 제목: {title}"
        if transcript:
            text += "\n\n---- 참고용 대본(대사 원문 확인용) ----\n" + transcript
        # 짧은 영상은 초당 2프레임으로 더 촘촘히 본다
        fps = 2 if (duration and duration <= 180) else None
        out = Gemini().generate(with_text(video(fps), text), models=PROMPT_MODELS)
        write(out_path, out + "\n")
    else:
        if not transcript and IS_YT:
            transcript = captions_transcript()
            if transcript:
                write(tpath, transcript + "\n")
        g = Gemini()
        if transcript:
            out = g.generate([{"text": f"{PROMPTS[TASK]}\n\n영상 제목: {title}\n\n---- 대본 ----\n{transcript}"}])
        else:
            out = g.generate(with_text(video(), f"{PROMPTS[TASK]}\n\n영상 제목: {title}"), long_video=IS_YT)
        write(out_path, out + "\n")

    err = os.path.join(VDIR, f"error-{TASK}.json")
    if os.path.exists(err):
        os.remove(err)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        traceback.print_exc()
        write(os.path.join(VDIR, f"error-{TASK}.json"), json.dumps(
            {"request_id": REQUEST_ID, "task": TASK, "message": str(e)[:500], "at": now_iso()},
            ensure_ascii=False, indent=2))
        # 오류 파일을 커밋해 앱이 원인을 보여줄 수 있게 정상 종료
        sys.exit(0)
