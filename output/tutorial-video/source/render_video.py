#!/usr/bin/env python3
"""Build a narrated LexFlow tutorial from real UI screenshots and scene notes.

Only Pillow, macOS say, ffmpeg and ffprobe are required. Run with the bundled
Python shown in README.md. All generated media stays inside output/tutorial-video.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import threading
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

SOURCE = Path(__file__).resolve().parent
ROOT = SOURCE.parent
ASSETS = ROOT / "assets"
CACHE = SOURCE / "cache"
MANIFEST = ROOT / "制作清单.json"
FFMPEG = "/usr/local/bin/ffmpeg"
FFPROBE = "/usr/local/bin/ffprobe"
VOICE, RATE = "Tingting", 155
WIDTH, HEIGHT, FPS = 1920, 1080, 15
STYLE_VERSION = "lexflow-v5"
NAVY, PANEL, GOLD = "#101A30", "#18263F", "#D9B774"
WHITE, MUTED, LINE = "#F6F4EE", "#A9B5CA", "#33425A"
FONT_PATH = "/System/Library/Fonts/Hiragino Sans GB.ttc"
MONO_PATH = "/System/Library/Fonts/SFNSMono.ttf"
PRINT_LOCK = threading.Lock()
SAY_LOCK = threading.Lock()
AUDIO_LOCKS: dict[str, threading.Lock] = {}
AUDIO_LOCK_GUARD = threading.Lock()


def log(message):
    with PRINT_LOCK:
        print(message, flush=True)


def command(args):
    result = subprocess.run([str(x) for x in args], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f"命令失败：{' '.join(str(x) for x in args[:8])}\n"
                           f"{result.stderr[-6000:]}")
    return result.stdout


def probe(path):
    value = command([FFPROBE, "-v", "error", "-show_format", "-show_streams", "-show_chapters",
                     "-of", "json", path])
    return json.loads(value)


def duration(path):
    return float(probe(path)["format"]["duration"])


def atomic_json(path, data):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def font(size, mono=False):
    return ImageFont.truetype(MONO_PATH if mono else FONT_PATH, int(size))


def text_width(text, face):
    return face.getlength(text)


def tokens(text):
    # Keep identifiers, URLs and English words intact when breaking Chinese text.
    return re.findall(r"[A-Za-z0-9_][A-Za-z0-9_./:@?=&%#~+\-$\\()\[\]{}<>|]*|\s+|.", text)


def wrap_text(text, face, width):
    rows, row = [], ""
    for token in tokens(str(text)):
        candidate = row + token
        if row and text_width(candidate, face) > width:
            rows.append(row.rstrip())
            row = token.lstrip()
        else:
            row = candidate
    if row or not rows:
        rows.append(row.rstrip())
    return rows


def fitted_text(draw, text, box, size, fill=WHITE, min_size=20, spacing=9,
                max_lines=None, mono=False, align="left"):
    x, y, w, h = box
    for candidate in range(size, min_size - 1, -1):
        face = font(candidate, mono)
        rows = wrap_text(text, face, w)
        line_h = candidate + spacing
        if ((not max_lines or len(rows) <= max_lines) and len(rows) * line_h <= h
                and all(text_width(r, face) <= w + 0.5 for r in rows)):
            for i, row in enumerate(rows):
                offset = (w - text_width(row, face)) / 2 if align == "center" else 0
                draw.text((x + offset, y + i * line_h), row, font=face, fill=fill)
            return candidate, len(rows)
    raise ValueError(f"文字放不进画面，请缩短文案：{text[:100]}")


def subtitle_segments(text):
    """Prefer punctuation boundaries; never cut inside an English token."""
    pieces = [p.strip() for p in re.split(r"(?<=[。！？；，、])|\n+", text) if p.strip()]
    units = []
    for piece in pieces:
        if len(piece) <= 42:
            units.append(piece)
            continue
        current = ""
        for token in tokens(piece):
            if current and len(current + token) > 40:
                units.append(current.strip())
                current = token.lstrip()
            else:
                current += token
        if current.strip():
            units.append(current.strip())
    output, pending = [], ""
    for piece in units:
        if pending and len(pending + piece) > 42:
            output.append(pending)
            pending = ""
        pending += piece
        if len(pending) >= 22 and pending[-1:] in "。！？；":
            output.append(pending)
            pending = ""
    if pending:
        if output and len(pending) < 12 and len(output[-1] + pending) <= 44:
            output[-1] += pending
        else:
            output.append(pending)
    return output


def spoken_text(text):
    replacements = [(r"API\s*Key", "接口密钥"), (r"API", "接口"),
                    (r"AI", "人工智能"), (r"JWT", "身份令牌"),
                    (r"SSE", "服务器推送事件"), (r"URL", "网址"),
                    (r"JSON", "杰森格式")]
    for pattern, value in replacements:
        text = re.sub(r"(?<![A-Za-z0-9_])"+pattern+r"(?![A-Za-z0-9_])", value, text, flags=re.I)
    return text


def scene_inputs():
    path = SOURCE / "scenes.json"
    scenes = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(scenes, list) or not scenes:
        raise ValueError("scenes.json 必须是非空镜头数组")
    used = set()
    for scene in scenes:
        for key in ("id", "chapter", "title", "subtitle", "bullets", "narration", "visual"):
            if key not in scene:
                raise ValueError(f"镜头缺少字段 {key}：{scene}")
        if len(scene["bullets"]) != 3:
            raise ValueError(f"镜头 {scene['id']} 的 bullets 必须正好三条")
        if str(scene["id"]) in used:
            raise ValueError(f"镜头 id 重复：{scene['id']}")
        used.add(str(scene["id"]))
        if not str(scene["narration"]).strip():
            raise ValueError(f"镜头 {scene['id']} 没有旁白")
    return scenes


def background():
    im = Image.new("RGB", (WIDTH, HEIGHT), NAVY)
    d = ImageDraw.Draw(im)
    # Restrained geometric background; no font rendering depends on ffmpeg.
    for y in range(HEIGHT):
        t = y / HEIGHT
        d.line((0, y, WIDTH, y), fill=(16 + int(4*t), 26 + int(9*t), 48 + int(10*t)))
    d.ellipse((1420, -380, 2250, 450), outline="#26354D", width=2)
    d.ellipse((1500, -300, 2170, 370), outline="#26354D", width=2)
    d.line((72, 181, 1848, 181), fill=LINE, width=2)
    return im


def pill(draw, text, box, size=25, fill=GOLD, bg="#2D3040"):
    x, y, w, h = box
    draw.rounded_rectangle((x, y, x+w, y+h), radius=h//2, fill=bg)
    fitted_text(draw, text, (x+15, y+9, w-30, h-10), size, fill, min_size=18,
                spacing=3, max_lines=1, align="center")


def shell_words(line):
    words, current, quote, escaped = [], "", None, False
    for char in line:
        if escaped:
            current += char
            escaped = False
        elif char == "\\":
            current += char
            escaped = True
        elif char in "\"'`":
            current += char
            if quote == char:
                quote = None
            elif quote is None:
                quote = char
        elif char.isspace() and quote is None:
            if current:
                words.append(current)
                current = ""
        else:
            current += char
    if current:
        words.append(current)
    return words


def code_rows(lines, face, width):
    rows = []
    for line in lines:
        for original in str(line).splitlines() or [""]:
            if text_width(original, face) <= width:
                rows.append(original)
                continue
            words = shell_words(original)
            row, continuation = "", False
            for word in words:
                candidate = (row + " " + word).strip()
                indent = "  " if continuation else ""
                if row and text_width(indent + candidate + " \\", face) > width:
                    rows.append(indent + row + " \\")
                    continuation, row = True, word
                else:
                    row = candidate
            rows.append(("  " if continuation else "") + row)
    return rows


def terminal(draw, lines, box):
    x, y, w, h = box
    draw.rounded_rectangle((x, y, x+w, y+h), radius=20, fill="#0A1221", outline=LINE, width=2)
    for i, color in enumerate(("#E97872", "#DFB96B", "#79B99D")):
        draw.ellipse((x+23+i*26, y+22, x+36+i*26, y+35), fill=color)
    draw.text((x+115, y+14), "终端 / 配置示例", font=font(24), fill=MUTED)
    has_chinese = bool(re.search(r"[\u3400-\u9fff]", "\n".join(lines)))
    for size in range(32, 17, -1):
        face = font(size, mono=not has_chinese)
        rows = code_rows(lines, face, w-56)
        if len(rows) * (size+14) <= h-82 and all(text_width(r, face) <= w-56 for r in rows):
            for i, row in enumerate(rows):
                draw.text((x+28, y+66+i*(size+14)), row, font=face, fill="#D9E5EF")
            return
    raise ValueError("终端文案过长，无法在不截断的情况下显示；请拆成多个镜头")


def screenshot_path(scene):
    key = str(scene.get("visual", "slide"))
    return ASSETS / (key if key.lower().endswith(".png") else key + ".png")


def render_base(scene, index, total):
    im = background()
    d = ImageDraw.Draw(im)
    d.text((72, 41), "LEXFLOW", font=font(31), fill=GOLD)
    fitted_text(d, str(scene["chapter"]), (282, 48, 1080, 45), 27, MUTED, max_lines=1)
    pill(d, "教学演示 · 虚构数据", (1487, 38, 361, 52), 27)
    fitted_text(d, str(scene["title"]), (70, 103, 1450, 67), 47, min_size=30, max_lines=1)
    d.text((1700, 120), f"{index+1:02d} / {total:02d}", font=font(25), fill=MUTED)
    x, y, w, h = 72, 213, 1286, 658
    d.rounded_rectangle((x, y, x+w, y+h), radius=22, fill=PANEL, outline=LINE, width=2)
    image_path = screenshot_path(scene)
    is_ui = scene["visual"] != "slide" and image_path.is_file()
    if is_ui:
        d.rounded_rectangle((x+1, y+1, x+w-1, y+49), radius=20, fill="#26334B")
        d.rectangle((x+1, y+26, x+w-1, y+49), fill="#26334B")
        d.text((x+22, y+12), "LexFlow  /  真实前端界面", font=font(22), fill="#D5DCE5")
        source = Image.open(image_path).convert("RGB")
        crop_x, crop_y = 0, 0
        if scene.get("crop"):
            crop_x, crop_y, crop_w, crop_h = [int(v) for v in scene["crop"]]
            left, top = max(0, crop_x), max(0, crop_y)
            right, bottom = min(source.width, crop_x+crop_w), min(source.height, crop_y+crop_h)
            if right <= left or bottom <= top:
                raise ValueError(f"镜头 {scene['id']} 的 crop 超出截图范围")
            source = source.crop((left, top, right, bottom))
            crop_x, crop_y = left, top
        content = ImageOps.contain(source, (w-22, h-73), Image.Resampling.LANCZOS)
        px = x + (w-content.width)//2
        py = y+57+(h-73-content.height)//2
        im.paste(content, (px, py))
        if scene.get("focus"):
            fx, fy, fw, fh = [float(v) for v in scene["focus"]]
            fx, fy = fx-crop_x, fy-crop_y
            scale = content.width / source.width
            bounds = (max(px, px+fx*scale), max(py, py+fy*scale),
                      min(px+content.width, px+(fx+fw)*scale),
                      min(py+content.height, py+(fy+fh)*scale))
            if bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                overlay = Image.new("RGBA", im.size)
                od = ImageDraw.Draw(overlay)
                od.rounded_rectangle(bounds, radius=8, outline=GOLD, width=5, fill=(217, 183, 116, 16))
                im = Image.alpha_composite(im.convert("RGBA"), overlay).convert("RGB")
                d = ImageDraw.Draw(im)
    else:
        d.text((x+37, y+29), "LEXFLOW  /  分步讲解", font=font(25), fill=GOLD)
        fitted_text(d, str(scene["subtitle"]), (x+37, y+87, w-74, 110), 43,
                    min_size=29, max_lines=2, spacing=11)
        if scene.get("code"):
            terminal(d, scene["code"], (x+30, y+223, w-60, h-255))
        else:
            for i, bullet in enumerate(scene["bullets"]):
                by = y+240+i*121
                d.rounded_rectangle((x+33, by, x+w-33, by+96), radius=15, fill="#22314B")
                d.text((x+54, by+24), f"0{i+1}", font=font(34), fill=GOLD)
                fitted_text(d, str(bullet), (x+132, by+21, w-205, 65), 33,
                            min_size=23, max_lines=2, spacing=5)
    rx, ry, rw = 1390, 213, 458
    d.rounded_rectangle((rx, ry, rx+rw, ry+658), radius=22, fill=PANEL, outline=LINE, width=2)
    d.text((rx+28, ry+26), "本节操作要点", font=font(29), fill=GOLD)
    fitted_text(d, str(scene["subtitle"]), (rx+28, ry+83, rw-56, 104), 26,
                MUTED, min_size=21, max_lines=3, spacing=8)
    for i, bullet in enumerate(scene["bullets"]):
        by = ry+222+i*112
        d.ellipse((rx+28, by+4, rx+65, by+41), fill="#354057")
        d.text((rx+39, by+6), str(i+1), font=font(23), fill=GOLD)
        fitted_text(d, str(bullet), (rx+83, by+4, rw-112, 92), 28,
                    min_size=21, max_lines=3, spacing=7)
    d.text((rx+29, ry+605), "按字幕与配音逐步操作", font=font(21), fill=MUTED)
    note = "真实前端截图 + 逐步中文配音 · AI 输出如有展示，均为教学示例"
    d.text((77, 889), note, font=font(20), fill=MUTED)
    return im


def render_frame(base, scene, subtitle, scene_index, total, segment_index, segments):
    im = base.copy()
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((72, 928, 1848, 1046), radius=19, fill="#080F1E", outline="#33415A", width=1)
    fitted_text(d, subtitle, (112, 941, 1696, 103), 40, min_size=30,
                max_lines=2, spacing=8, align="center")
    # Each subtitle changes both overall progress and the current step marker.
    progress = (scene_index+(segment_index+1)/segments)/total
    d.rounded_rectangle((73, 1062, 1848, 1068), radius=3, fill="#344158")
    d.rounded_rectangle((73, 1062, 73+1775*progress, 1068), radius=3, fill=GOLD)
    active = min(2, int(segment_index/max(segments, 1)*3))
    for i in range(3):
        cx = 1418+18
        cy = 213+222+i*112+22
        if i == active:
            d.ellipse((cx-23, cy-23, cx+23, cy+23), outline=GOLD, width=3)
    return im


def render_cover(scenes):
    im = background()
    d = ImageDraw.Draw(im)
    d.text((92, 59), "LEXFLOW", font=font(43), fill=GOLD)
    pill(d, "详细使用教程 · 分步讲解", (1248, 57, 580, 65), 32)
    d.text((90, 235), "LexFlow", font=font(101), fill=WHITE)
    d.text((94, 365), "项目使用教程", font=font(82), fill=WHITE)
    fitted_text(d, "从本地启动到完整业务操作", (94, 502, 770, 130), 43,
                MUTED, min_size=34, max_lines=2, spacing=16)
    d.text((96, 697), "真实前端界面  /  中文慢速配音", font=font(30), fill=GOLD)
    d.text((96, 751), "60 个步骤 · 13 个章节 · 完整流程", font=font(28), fill=MUTED)
    ui_scene = next((s for s in scenes if s["visual"] != "slide" and screenshot_path(s).is_file()), None)
    d.rounded_rectangle((960, 237, 1828, 830), radius=26, fill=PANEL, outline=GOLD, width=2)
    if ui_scene:
        img = Image.open(screenshot_path(ui_scene)).convert("RGB")
        img = ImageOps.contain(img, (830, 540), Image.Resampling.LANCZOS)
        im.paste(img, (979+(830-img.width)//2, 261+(540-img.height)//2))
    else:
        d.text((1030, 344), "理解 → 操作 → 核对", font=font(43), fill=GOLD)
        for i, label in enumerate(("01  环境与登录", "02  核心业务流程", "03  配置与问题处理")):
            d.text((1030, 448+i*88), label, font=font(35), fill=WHITE)
    d.text((95, 964), "教学演示 · 虚构数据", font=font(27), fill=GOLD)
    d.text((1265, 965), "1920 × 1080  /  中文教程", font=font(25), fill=MUTED)
    im.save(ROOT / "封面.png")


def audio_segment(text):
    spoken = spoken_text(text)
    key = hashlib.sha256(f"{VOICE}|{RATE}|{spoken}".encode()).hexdigest()[:24]
    path = CACHE / "audio" / f"{key}.aiff"
    path.parent.mkdir(parents=True, exist_ok=True)
    with AUDIO_LOCK_GUARD:
        lock = AUDIO_LOCKS.setdefault(key, threading.Lock())
    with lock:
        if path.is_file():
            try:
                if duration(path) > 0.05:
                    return path, duration(path), spoken
            except (RuntimeError, ValueError, KeyError):
                path.unlink(missing_ok=True)
        tmp = path.with_suffix(".partial.aiff")
        # macOS 的同一语音服务在并行合成时偶尔挂起，语音串行生成，画面和编码仍可并行。
        with SAY_LOCK:
            for attempt in range(3):
                try:
                    result = subprocess.run(
                        ["/usr/bin/say", "-v", VOICE, "-r", str(RATE), "-o", str(tmp), spoken],
                        capture_output=True, text=True, timeout=30,
                    )
                    if result.returncode:
                        raise RuntimeError(f"中文配音失败：{result.stderr[-500:]}")
                    break
                except subprocess.TimeoutExpired:
                    tmp.unlink(missing_ok=True)
                    if attempt == 2:
                        raise RuntimeError(f"中文配音连续超时：{text}")
                    log(f"[语音重试] {text[:30]}")
        length = duration(tmp)
        if length <= 0.05:
            raise RuntimeError(f"语音为空：{text}")
        tmp.replace(path)
        return path, length, spoken


def concat_line(path):
    return "file '" + str(path.resolve()).replace("'", "'\\''") + "'\n"


def prepare_scene(item, total, threads):
    index, scene = item
    slug = re.sub(r"[^a-zA-Z0-9_-]+", "_", str(scene["id"])) or "scene"
    folder = CACHE / "scenes" / f"{index+1:03d}_{slug}"
    folder.mkdir(parents=True, exist_ok=True)
    log(f"[准备 {index+1:02d}/{total:02d}] {scene['title']}")
    segments = subtitle_segments(scene["narration"])
    base = render_base(scene, index, total)
    prepared, offset = [], 0.0
    for i, text in enumerate(segments):
        audio, voice_duration, spoken = audio_segment(text)
        pause = 0.34 if i == len(segments)-1 else 0.20
        frame = folder / f"frame_{i+1:02d}.png"
        render_frame(base, scene, text, index, total, i, len(segments)).save(frame, optimize=False)
        prepared.append({"text": text, "spoken_text": spoken, "audio": str(audio),
                         "voice_duration": voice_duration, "pause": pause,
                         "start": offset, "duration": voice_duration+pause, "frame": str(frame)})
        offset += voice_duration+pause
    wav = folder / "narration.wav"
    audio_signature = hashlib.sha256(json.dumps(
        [{k: s[k] for k in ("audio", "voice_duration", "pause")} for s in prepared],
        sort_keys=True).encode()).hexdigest()
    signature_file = folder / "audio.signature"
    if not wav.is_file() or not signature_file.is_file() or signature_file.read_text() != audio_signature:
        args = [FFMPEG, "-y", "-hide_banner", "-loglevel", "error", "-threads", str(threads)]
        for segment in prepared:
            args += ["-i", segment["audio"]]
        filters = []
        for i, segment in enumerate(prepared):
            filters.append(f"[{i}:a]apad=pad_dur={segment['pause']:.6f},"
                           f"aformat=sample_fmts=s16:sample_rates=24000:channel_layouts=mono[a{i}]")
        inputs = "".join(f"[a{i}]" for i in range(len(prepared)))
        filters.append(inputs+f"concat=n={len(prepared)}:v=0:a=1[out]")
        args += ["-filter_complex_threads", str(threads), "-filter_complex", ";".join(filters),
                 "-map", "[out]", "-c:a", "pcm_s16le", "-ar", "24000", "-ac", "1", wav]
        command(args)
        signature_file.write_text(audio_signature)
    actual = duration(wav)
    # Account for tiny sample-rate conversion rounding in the final still frame.
    prepared[-1]["duration"] += actual-offset
    ffconcat = folder / "frames.ffconcat"
    content = "ffconcat version 1.0\n"
    for segment in prepared:
        content += concat_line(Path(segment["frame"]))
        content += f"duration {segment['duration']:.8f}\n"
    content += concat_line(Path(prepared[-1]["frame"]))
    ffconcat.write_text(content, encoding="utf-8")
    fingerprint_data = {"style": STYLE_VERSION, "scene": scene, "segments": prepared,
                        "wav_size": wav.stat().st_size}
    source_image = screenshot_path(scene)
    if source_image.is_file():
        fingerprint_data["screenshot"] = [source_image.stat().st_size, source_image.stat().st_mtime_ns]
    fingerprint = hashlib.sha256(json.dumps(fingerprint_data, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    result = {**scene, "index": index, "segments": prepared, "duration": actual,
              "audio_file": str(wav), "concat_file": str(ffconcat), "folder": str(folder),
              "video_file": str(folder / "scene.mp4"), "fingerprint": fingerprint,
              "visual_type": "真实前端截图" if source_image.is_file() and scene["visual"] != "slide" else "讲解卡片"}
    atomic_json(folder / "prepared.json", result)
    log(f"[准备完成 {index+1:02d}/{total:02d}] {actual:.1f} 秒 / {len(segments)} 条同步字幕")
    return result


def prepare(scenes, workers, threads):
    render_cover(scenes)
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(prepare_scene, item, len(scenes), threads) for item in enumerate(scenes)]
        results = [future.result() for future in futures]
    manifest = {"schema_version": 1, "style_version": STYLE_VERSION,
                "title": "LexFlow 详细使用教程 · 分步讲解", "resolution": [WIDTH, HEIGHT],
                "fps": FPS, "voice": VOICE, "rate": RATE,
                "presentation": "真实前端截图与讲解卡片，配以逐步中文配音；非连续录屏",
                "disclosure": "教学演示采用虚构数据，AI 输出如有展示均为示例结果。",
                "scenes": results, "prepared_duration": sum(s["duration"] for s in results)}
    atomic_json(MANIFEST, manifest)
    write_documents(manifest, encoded=False)
    log(f"准备完成：{len(results)} 个镜头，预计 {manifest['prepared_duration']/60:.1f} 分钟。")
    return manifest


def encode_scene(scene, total, threads):
    output = Path(scene["video_file"])
    signature = output.with_suffix(".signature")
    if output.is_file() and signature.is_file() and signature.read_text() == scene["fingerprint"]:
        log(f"[复用编码 {scene['index']+1:02d}/{total:02d}] {scene['title']}")
    else:
        log(f"[编码 {scene['index']+1:02d}/{total:02d}] {scene['title']}")
        tmp = output.with_name("scene.partial.mp4")
        command([FFMPEG, "-y", "-hide_banner", "-loglevel", "error", "-threads", str(threads),
                 "-f", "concat", "-safe", "0", "-i", scene["concat_file"],
                 "-i", scene["audio_file"], "-map", "0:v:0", "-map", "1:a:0",
                 "-vf", f"fps={FPS}", "-c:v", "libx264", "-preset", "fast", "-tune", "stillimage",
                 "-crf", "22", "-pix_fmt", "yuv420p", "-r", str(FPS),
                 "-threads", str(threads), "-filter_threads", str(threads),
                 "-c:a", "aac", "-b:a", "128k", "-ar", "24000", "-ac", "1",
                 "-t", f"{scene['duration']:.8f}", "-video_track_timescale", "15000",
                 "-movflags", "+faststart", tmp])
        tmp.replace(output)
        signature.write_text(scene["fingerprint"])
    result = dict(scene)
    result["encoded_duration"] = duration(output)
    return result


def clock_text(seconds, milliseconds=False):
    total_ms = round(seconds*1000)
    hh, remainder = divmod(total_ms, 3600000)
    mm, remainder = divmod(remainder, 60000)
    ss, ms = divmod(remainder, 1000)
    return f"{hh:02d}:{mm:02d}:{ss:02d}" + (f",{ms:03d}" if milliseconds else "")


def timeline(manifest, encoded):
    start, results = 0.0, []
    for scene in manifest["scenes"]:
        length = scene.get("encoded_duration", scene["duration"]) if encoded else scene["duration"]
        results.append((scene, start, length))
        start += length
    return results


def write_documents(manifest, encoded):
    rows = timeline(manifest, encoded)
    srt, cue = [], 1
    chapters = []
    for scene, start, length in rows:
        if not chapters or chapters[-1]["title"] != scene["chapter"]:
            chapters.append({"title": scene["chapter"], "start": start, "end": start+length})
        else:
            chapters[-1]["end"] = start+length
        for i, segment in enumerate(scene["segments"]):
            end = segment["start"]+segment["duration"]
            if i == len(scene["segments"])-1:
                end = length
            srt += [str(cue), f"{clock_text(start+segment['start'], True)} --> {clock_text(start+end, True)}",
                    segment["text"], ""]
            cue += 1
    (ROOT / "LexFlow-详细使用教程.srt").write_text("\n".join(srt), encoding="utf-8")
    total = sum(length for _, _, length in rows)
    timings = ["# LexFlow 教程章节时间轴", "",
               f"总时长：{clock_text(total)}（{'已编码真实时长' if encoded else '已合成配音时长，编码后自动校正'}）。", "",
               "视频形式：真实前端截图 + 分步中文配音与讲解卡片；非连续录屏。", "",
               "| 开始 | 结束 | 章节 |", "| --- | --- | --- |"]
    for chapter in chapters:
        timings.append(f"| {clock_text(chapter['start'])} | {clock_text(chapter['end'])} | {chapter['title']} |")
    timings += ["", "## 分镜索引", "", "| 时间 | 镜头 | 展示形式 |", "| --- | --- | --- |"]
    for scene, start, length in rows:
        timings.append(f"| {clock_text(start)} | {scene['title']} | {scene['visual_type']} |")
    (ROOT / "章节时间轴.md").write_text("\n".join(timings)+"\n", encoding="utf-8")
    transcript = ["# LexFlow 详细使用教程完整讲解稿", "",
                  "教学演示使用虚构数据。界面截图来自本地前端；AI 输出如有展示均为教学示例。", "",
                  "旁白采用中文慢速讲解；API、AI 等术语在语音中分别读为“接口”“人工智能”。", ""]
    for scene, start, _ in rows:
        transcript += [f"## {clock_text(start)} · {scene['chapter']} / {scene['title']}", "",
                       scene["subtitle"], ""]
        transcript += [f"{i+1}. {item}" for i, item in enumerate(scene["bullets"])]
        transcript += ["", "旁白：", "", scene["narration"], ""]
        if scene.get("code"):
            transcript += ["命令或配置：", "", "```sh", *scene["code"], "```", ""]
    (ROOT / "完整讲解稿.md").write_text("\n".join(transcript), encoding="utf-8")
    metadata = [";FFMETADATA1", "title=LexFlow 详细使用教程 · 分步讲解",
                "comment=真实前端截图与逐步中文配音；教学演示采用虚构数据。"]
    for chapter in chapters:
        title = str(chapter["title"]).replace("\\", "\\\\").replace("=", "\\=").replace(";", "\\;").replace("#", "\\#").replace("\n", " ")
        metadata += ["[CHAPTER]", "TIMEBASE=1/1000", f"START={round(chapter['start']*1000)}",
                     f"END={round(chapter['end']*1000)}", f"title={title}"]
    (CACHE / "chapters.ffmetadata").write_text("\n".join(metadata)+"\n", encoding="utf-8")
    manifest["chapters"] = chapters
    manifest["total_duration"] = total


def encode(manifest, workers, threads):
    if manifest.get("style_version") != STYLE_VERSION:
        raise ValueError("清单使用旧版画面，请重新运行 --prepare")
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(encode_scene, s, len(manifest["scenes"]), threads) for s in manifest["scenes"]]
        manifest["scenes"] = [future.result() for future in futures]
    write_documents(manifest, encoded=True)
    concat = CACHE / "all_scenes.ffconcat"
    concat.write_text("ffconcat version 1.0\n"+"".join(concat_line(Path(s["video_file"]))
                                                     for s in manifest["scenes"]), encoding="utf-8")
    target = ROOT / "LexFlow-详细使用教程.mp4"
    temporary = ROOT / "LexFlow-详细使用教程.partial.mp4"
    command([FFMPEG, "-y", "-hide_banner", "-loglevel", "error", "-threads", str(threads),
             "-f", "concat", "-safe", "0", "-i", concat,
             "-f", "ffmetadata", "-i", CACHE / "chapters.ffmetadata",
             "-i", ROOT / "LexFlow-详细使用教程.srt", "-map", "0:v:0", "-map", "0:a:0", "-map", "2:0",
             "-map_metadata", "1", "-map_chapters", "1", "-c:v", "copy", "-c:a", "copy",
             "-c:s", "mov_text", "-metadata:s:s:0", "language=zho",
             "-metadata:s:s:0", "title=中文字幕", "-disposition:s:0", "0", "-movflags", "+faststart", temporary])
    info = probe(temporary)
    video = next(s for s in info["streams"] if s["codec_type"] == "video")
    audio = next(s for s in info["streams"] if s["codec_type"] == "audio")
    subtitle = next(s for s in info["streams"] if s["codec_type"] == "subtitle")
    if (video["width"], video["height"], video["pix_fmt"]) != (WIDTH, HEIGHT, "yuv420p"):
        raise RuntimeError("最终视频的尺寸或像素格式不符合要求")
    if audio["codec_name"] != "aac" or subtitle["codec_name"] != "mov_text":
        raise RuntimeError("最终视频缺少 AAC 音轨或 mov_text 字幕轨")
    if len(info.get("chapters", [])) != len(manifest["chapters"]):
        raise RuntimeError("最终视频的章节数量不符合制作清单")
    temporary.replace(target)
    manifest["output_video"] = str(target)
    manifest["final_probe"] = info
    manifest["final_duration"] = float(info["format"]["duration"])
    atomic_json(MANIFEST, manifest)
    log(f"完成：{target}\n时长 {clock_text(manifest['final_duration'])}，"
        f"文件 {target.stat().st_size/1024/1024:.1f} MB，含中文配音、内嵌字幕和章节。")


def preview(scenes):
    render_cover(scenes)
    columns, tile_w, tile_h = 4, 480, 270
    rows = math.ceil(len(scenes)/columns)
    sheet = Image.new("RGB", (columns*tile_w, rows*tile_h), NAVY)
    for i, scene in enumerate(scenes):
        segments = subtitle_segments(scene["narration"])
        frame = render_frame(render_base(scene, i, len(scenes)), scene, segments[0],
                             i, len(scenes), 0, len(segments))
        frame.thumbnail((tile_w, tile_h), Image.Resampling.LANCZOS)
        sheet.paste(frame, ((i%columns)*tile_w, (i//columns)*tile_h))
    sheet.save(ROOT / "联系表.jpg", quality=93)
    log(f"封面：{ROOT/'封面.png'}\n联系表：{ROOT/'联系表.jpg'}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--prepare", action="store_true", help="仅合成配音、字幕画面和制作清单")
    group.add_argument("--encode", action="store_true", help="依据现有制作清单编码与封装")
    group.add_argument("--all", action="store_true", help="完整准备并编码（默认）")
    group.add_argument("--preview", action="store_true", help="仅生成封面和全部镜头联系表，不合成语音")
    parser.add_argument("--workers", type=int, default=3, help="并行场景，范围 1–3")
    parser.add_argument("--threads", type=int, default=4, help="每次 ffmpeg 使用的线程，范围 1–4")
    args = parser.parse_args()
    workers, threads = max(1, min(args.workers, 3)), max(1, min(args.threads, 4))
    ROOT.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    if args.encode:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        encode(manifest, workers, threads)
        return
    scenes = scene_inputs()
    if args.preview:
        preview(scenes)
        return
    manifest = prepare(scenes, workers, threads)
    if not args.prepare:
        encode(manifest, workers, threads)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, OSError, KeyError, StopIteration) as exc:
        log(f"制作失败：{exc}")
        sys.exit(1)
