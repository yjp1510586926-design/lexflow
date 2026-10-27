# LexFlow 中文详细教程视频制作

本目录将真实前端截图、分镜讲解、中文语音和同步字幕制作成 1920 × 1080 视频。画面使用深蓝金色风格，固定标注“教学演示 · 虚构数据”。它是截图与讲解卡片组成的分步教程，不是连续操作录屏。

## 运行环境

- Python：`/Users/yangjinpeng/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3`
- Python 包：Pillow。
- 语音：macOS `/usr/bin/say`，Tingting，语速 155。
- 视频：`/usr/local/bin/ffmpeg`、`/usr/local/bin/ffprobe`，使用 libx264 和 AAC。
- 字体：macOS Hiragino Sans GB 与 SFNSMono。

本机 ffmpeg 没有 drawtext / subtitles 滤镜，因此所有标题、步骤和中文字幕均由 Pillow 直接烘焙到 PNG 中。另生成独立 SRT，并在最终 MP4 中附加可切换的 mov_text 字幕轨。播放器同时打开字幕轨会与画面字幕重复，默认未启用附加字幕轨。

## 输入

`scenes.json` 是非空数组。每个镜头需要 `id`、`chapter`、`title`、`subtitle`、`bullets`（正好三条）、`narration`、`visual` 字段。可选 `code` 字符串数组、`crop` 裁切区域与 `focus` 高亮区域，均使用原始截图像素坐标 `[x,y,width,height]`。先裁切，再缩放；focus 会自动扣除裁切偏移。未提供 crop 时保持完整截图。

```json
[
  {
    "id": "login",
    "chapter": "第二章 · 登录与账号",
    "title": "登录并认识首页",
    "subtitle": "确认账号状态后进入工作台",
    "bullets": ["填写用户名和密码", "点击登录", "核对首页与账号信息"],
    "narration": "先打开本地前端页面，输入教学账号和密码。确认内容后点击登录按钮，等待页面跳转到工作台。接下来核对左侧菜单与右上角账号状态，确保已经进入正确的系统。",
    "visual": "login",
    "focus": [100, 80, 400, 220]
  }
]
```

截图位于 `../assets/{visual}.png`。`visual` 为 `slide` 或截图不存在时，自动生成讲解卡片；提供 `code` 时生成终端卡片。命令过长时只在引号外的空白处分行，并显示 shell 续行符；无法完整显示时明确报错，不截断内容。请将非常长的配置或命令分成多个镜头。

## 制作命令

在本目录运行：

```sh
PYTHON=/Users/yangjinpeng/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3

# 快速检查全部画面，不生成语音或视频。
"$PYTHON" render_video.py --preview

# 先合成并缓存中文语音、同步字幕帧和制作清单。
"$PYTHON" render_video.py --prepare

# 确认画面后，基于现有清单编码视频并封装字幕与章节。
"$PYTHON" render_video.py --encode

# 或一步执行完整制作流程。
"$PYTHON" render_video.py --all
```

省略参数等同 `--all`。`--workers 1` 可降低并行数；默认同时处理三个场景。`--threads` 限制每个 ffmpeg 的线程数，最多为四。日志实时输出。

## 同步与缓存

旁白优先按中文标点组合成约 22–42 字的短句，英文单词、接口路径和标识符保持完整。每个短句单独调用 say，再用 ffprobe 读取真实时长。短句之间加入 0.20 秒停顿，镜头末尾加入 0.34 秒停顿。每条字幕有独立画面，底部进度条和右侧步骤标记随字幕变化。

语音中会将 API Key、API、AI、JWT、SSE、URL、JSON 转成更适合中文朗读的词语；字幕保留原始文案。想调整措辞，应修改 `scenes.json`，而不是修改制作清单。

音频按“声音、语速、朗读文本”的内容哈希保存在 `cache/audio`。重复准备会复用已有语音；场景视频按分镜与截图指纹缓存。中断后重新运行相同命令即可继续。修改分镜或截图后先重新 `--prepare`；单独 `--encode` 使用的是上一次准备好的输入。

章节时间轴最初依据已合成配音时长生成；全部场景编码后会以真实场景视频时长校正。独立 SRT、MP4 中的字幕与章节同步使用校正后的时间轴。

## 输出

- `../LexFlow-详细使用教程.mp4`：H.264，1920 × 1080，15 fps，yuv420p，CRF 22，AAC 中文音轨，mov_text 中文字幕轨，章节元数据。
- `../LexFlow-详细使用教程.srt`：独立字幕。
- `../章节时间轴.md`：章节与镜头时间索引。
- `../完整讲解稿.md`：原始旁白、操作要点与命令。
- `../制作清单.json`：全部输入、短句时长、画面路径、章节与最终媒体探测信息。
- `../封面.png`：1920 × 1080 封面。
- `../联系表.jpg`：`--preview` 生成的全部镜头缩略图。

发布或分享前，建议抽查首尾、每章至少一个片段及长命令画面；检查音画同步、中文字体、界面敏感信息和数据披露。此脚本不连接真实业务数据、不调用模型、不上传视频。

## 本次生成说明

本次制作包含 60 个镜头、13 个章节。所有业务画面来自当前 React 前端连接的本地内存演示 API（`demo-server.cjs`），并非真实 Java / NestJS / 数据库业务验收。演示 API 只绑定本机 18081，使用虚构人物和材料，不加载项目的私密配置，也不调用外部模型；演示时的立即成功、报告内容与测试耗时均为模拟结果。真实部署步骤依据项目 `docs/mvp-deployment.md` 编写，视频制作期间未执行部署。

`scenes.json` 保存经真实界面复核后的操作文案、截图裁切与按钮标记。`事实核对.md` 与 `界面与接口核对.md` 记录校对依据。`../assets/教学示例-*.txt` 可用于跟练；内容全部虚构。

语音使用本机 Tingting 合成。为避免 macOS 同一语音服务并发挂起，语音生成已串行化，单条 30 秒超时后最多尝试三次；这些操作不联网、不调用模型。画面准备和视频编码仍可并行。

如需重新采图，可先在本目录执行 `node demo-server.cjs`，另在项目 `web` 目录执行 `LEXFLOW_API_URL=http://127.0.0.1:18081 npm run dev -- --port 18080 --strictPort`，再通过本地演示入口登录。机构编号为 1，任意非空演示账号和密码均可登录；这些凭据仅适用于演示 API，不适用于真实系统。重新采图后先运行 `--prepare`，再运行 `--encode`。
