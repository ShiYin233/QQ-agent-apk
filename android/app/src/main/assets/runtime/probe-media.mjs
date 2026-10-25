import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { compressImage } from '/opt/agent/src/image-compress.js';
import { gifToVideoDataUrl } from '/opt/agent/src/gif-to-video.js';
import { VideoReader } from '/opt/agent/src/video-reader.js';
import { getConfig, setRuntimeConfig } from '/opt/agent/src/config.js';
import { loadPlugins } from '/opt/agent/src/plugin-loader.js';
const directory = fs.mkdtempSync('/tmp/qqagent_media_probe_');
const report = { schema: 1, timestamp: Date.now(), executedOn: 'ANDROID_APPLICATION_PROCESS', qqMessagesSent: 0, modelCalls: 0, passed: false };
const files = ['source.ppm', 'source.png', 'source.gif', 'converted.mp4', 'compressed.jpg'];
const ffmpeg = args => execFileSync('/usr/bin/ffmpeg', ['-nostdin', '-hide_banner', '-loglevel', 'error', '-y', ...args], { timeout: 30000, stdio: 'pipe' });
const probe = file => JSON.parse(execFileSync('/usr/bin/ffprobe', ['-v', 'quiet', '-show_streams', '-show_format', '-print_format', 'json', file], { timeout: 15000 }));
try {
  report.ffmpegVersion = execFileSync('/usr/bin/ffmpeg', ['-version']).toString().split('\n')[0];
  const pixels = Buffer.alloc(1280 * 960 * 3); let random = 1234567;
  for (let index = 0; index < pixels.length; index++) { random = (Math.imul(random, 1664525) + 1013904223) >>> 0; pixels[index] = random >>> 24; }
  fs.writeFileSync(`${directory}/source.ppm`, Buffer.concat([Buffer.from('P6\n1280 960\n255\n'), pixels]));
  ffmpeg(['-i', `${directory}/source.ppm`, '-frames:v', '1', `${directory}/source.png`]);
  const image = fs.readFileSync(`${directory}/source.png`);
  const compressed = await compressImage(image, { maxDim: 480, maxBytes: 512 * 1024 });
  if (!compressed.compressed || compressed.buffer.length >= image.length) throw Error('Original image compression did not run');
  fs.writeFileSync(`${directory}/compressed.jpg`, compressed.buffer);
  const dims = probe(`${directory}/compressed.jpg`).streams[0];
  report.imageCompression = { sourceBytes: image.length, outputBytes: compressed.buffer.length, width: dims.width, height: dims.height, passed: Math.max(dims.width, dims.height) <= 480 };
  ffmpeg(['-f', 'lavfi', '-i', 'testsrc=size=640x361:rate=6', '-t', '1', `${directory}/source.gif`]);
  const gif = await gifToVideoDataUrl(fs.readFileSync(`${directory}/source.gif`));
  if (!gif) throw Error('Original GIF conversion did not produce video');
  fs.writeFileSync(`${directory}/converted.mp4`, Buffer.from(gif.dataUrl.split(',')[1], 'base64'));
  const video = probe(`${directory}/converted.mp4`).streams[0];
  report.gifConversion = { width: video.width, height: video.height, frames: Number(video.nb_frames), passed: Math.max(video.width, video.height) <= 480 && Number(video.nb_frames) <= 24 && video.width % 2 === 0 && video.height % 2 === 0 };
  const loaded = await loadPlugins({ roots: { plugins: '/opt/agent/plugins' }, log: () => {} });
  if (!loaded.loaded.some(item => item.id === 'android-video-frames')) throw Error('Android frame provider was not loaded');
  const cfg = structuredClone(getConfig()); cfg.api.videoMode = 'frames'; setRuntimeConfig(cfg);
  const reader = new VideoReader({ call: async () => ({}) });
  const frames = await reader.probe({ path: `${directory}/converted.mp4` }, { count: 3 });
  report.videoFrames = { route: frames.route, count: frames.frames?.length || 0, passed: frames.route === 'frames' && frames.frames?.length === 3 };
  report.passed = [report.imageCompression, report.gifConversion, report.videoFrames].every(item => item.passed);
  if (!report.passed) throw Error('Media bounds failed');
  console.log('QQAGENT_ANDROID_MEDIA_OK');
} catch (error) { report.errorType = error.constructor.name; process.exitCode = 1; }
finally {
  fs.writeFileSync('/qqagent-state/media-report.json', JSON.stringify(report, null, 2));
  for (const name of files) if (fs.existsSync(`${directory}/${name}`)) fs.unlinkSync(`${directory}/${name}`);
  fs.rmdirSync(directory);
}
