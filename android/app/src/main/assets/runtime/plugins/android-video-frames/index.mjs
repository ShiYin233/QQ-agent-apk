// SPDX-License-Identifier: MIT
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const run = promisify(execFile);
export function available() {
  try { for (const name of ['ffmpeg', 'ffprobe']) fs.accessSync(`/usr/bin/${name}`, fs.constants.X_OK); return { ok: true }; }
  catch { return { ok: false, reason: '请先在设置中安装安卓媒体依赖（FFmpeg）' }; }
}
export async function extractFrames({ filePath, count = 3, durationSec = 0 }) {
  if (!available().ok) return { frames: [], times: [], error: available().reason };
  const resolved = fs.realpathSync(String(filePath));
  const stat = fs.statSync(resolved);
  if (!stat.isFile() || stat.size > 64 * 1024 * 1024) return { frames: [], times: [], error: '视频超过 64 MB 抽帧上限' };
  const total = Math.min(8, Math.max(1, Math.round(Number(count) || 3)));
  const duration = Math.max(0, Math.min(3600, Number(durationSec) || 0));
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'qqa_android_frames_'));
  const frames = [], times = [], files = [];
  try {
    for (let index = 0; index < total; index++) {
      const at = duration > 0 ? Math.min(Math.max(0, duration - 0.05), duration * (index + 0.5) / total) : index;
      const output = path.join(directory, `${index}.jpg`); files.push(output);
      await run('/usr/bin/ffmpeg', ['-nostdin', '-loglevel', 'error', '-y', '-protocol_whitelist', 'file,pipe,crypto,data',
        '-ss', String(at), '-i', resolved, '-frames:v', '1', '-vf', "scale='min(768,iw)':'min(768,ih)':force_original_aspect_ratio=decrease", '-q:v', '4', output],
        { timeout: 20000, maxBuffer: 1024 * 1024 });
      if (!fs.existsSync(output)) continue;
      const image = fs.readFileSync(output);
      if (!image.length || image.length > 2 * 1024 * 1024) continue;
      frames.push(`data:image/jpeg;base64,${image.toString('base64')}`); times.push(at);
    }
    return { frames, times, ...(frames.length ? {} : { error: '视频没有可解码画面' }) };
  } catch { return { frames: [], times: [], error: '视频解码失败或超时' }; }
  finally { for (const file of files) if (fs.existsSync(file)) fs.unlinkSync(file); fs.rmdirSync(directory); }
}
export const providers = { 'video.frames.available': available, 'video.frames': extractFrames };
