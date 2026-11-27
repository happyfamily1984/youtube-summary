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
TASK = os.environ["TASK"].strip()
REQUEST_ID = os.environ.get("REQUEST_ID", "").strip()
LANG = (os.environ.get("LANGUAGE") or "ko").strip()
MODELS = [m.strip() for m in (os.environ.get("GEMINI_MODELS") or
          "gemini-flash-latest,gemini-2.5-flash,gemini-flash-lite-latest").split(",") if m.strip()]

VDIR = os.path.join(DATA, "videos", VIDEO_ID)
STATE_PATH = os.path.join(DATA, "state", "gemini.json")
FILES = {"transcript": "transcript.md", "summary": "summary.md", "instructions": "instructions.md"}
URL = f"https://www.youtube.com/watch?v={VIDEO_ID}"

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

    def generate(self, parts, long_video=False):
        body = {
            "contents": [{"role": "user", "parts": parts}],
            "generationConfig": {"temperature": 0.2, "maxOutputTokens": 65536},
        }
        if long_video:
            body["generationConfig"]["mediaResolution"] = "MEDIA_RESOLUTION_LOW"
        errors = []
        for model in MODELS:
            model_missing = False
            for n in self.order():
                key = self.keys[n - 1]
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

def video_meta():
    path = os.path.join(VDIR, "meta.json")
    meta = None
    try:
        meta = json.loads(read(path) or "null")
    except ValueError:
        pass
    if not meta or not meta.get("title"):
        title, channel = "", ""
        try:
            r = requests.get("https://www.youtube.com/oembed",
                             params={"format": "json", "url": URL}, timeout=20)
            if r.ok:
                title, channel = r.json().get("title", ""), r.json().get("author_name", "")
        except requests.RequestException:
            pass
        meta = {"id": VIDEO_ID, "title": title, "channel": channel, "url": URL,
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


def video_part():
    return {"file_data": {"file_uri": URL, "mime_type": "video/*"}}


# ---------------- 실행 ----------------

def main():
    if TASK not in FILES:
        raise RuntimeError(f"알 수 없는 작업: {TASK}")
    meta = video_meta()
    title = meta.get("title") or VIDEO_ID
    out_path = os.path.join(VDIR, FILES[TASK])
    tpath = os.path.join(VDIR, "transcript.md")
    transcript = read(tpath)

    if TASK == "transcript":
        text = captions_transcript()
        if not text:
            text = Gemini().generate([video_part(), {"text": PROMPTS["transcript"]}], long_video=True)
        write(out_path, text + "\n")
    else:
        if not transcript:
            transcript = captions_transcript()
            if transcript:
                write(tpath, transcript + "\n")
        g = Gemini()
        if transcript:
            text = g.generate([{"text": f"{PROMPTS[TASK]}\n\n영상 제목: {title}\n\n---- 대본 ----\n{transcript}"}])
        else:
            text = g.generate([video_part(), {"text": f"{PROMPTS[TASK]}\n\n영상 제목: {title}"}], long_video=True)
        write(out_path, text + "\n")

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
