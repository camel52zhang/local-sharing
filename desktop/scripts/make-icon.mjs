import fs from 'fs';
import zlib from 'zlib';
import { fileURLToPath } from 'url';
import path from 'path';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const outPath = path.join(scriptDir, 'icon-1024.png');

const SIZE = 1024;

// 像素缓冲：RGBA，行优先
const buf = Buffer.alloc(SIZE * SIZE * 4);

function setPixel(x, y, r, g, b) {
  if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return;
  const i = (y * SIZE + x) * 4;
  buf[i] = r;
  buf[i + 1] = g;
  buf[i + 2] = b;
  buf[i + 3] = 255;
}

function fillRect(x0, y0, x1, y1, r, g, b) {
  for (let y = Math.floor(y0); y < Math.ceil(y1); y++) {
    for (let x = Math.floor(x0); x < Math.ceil(x1); x++) {
      setPixel(x, y, r, g, b);
    }
  }
}

function sign(px, py, ax, ay, bx, by) {
  return (px - bx) * (ay - by) - (ax - bx) * (py - by);
}

function pointInTriangle(px, py, ax, ay, bx, by, cx, cy) {
  const d1 = sign(px, py, ax, ay, bx, by);
  const d2 = sign(px, py, bx, by, cx, cy);
  const d3 = sign(px, py, cx, cy, ax, ay);
  const hasNeg = d1 < 0 || d2 < 0 || d3 < 0;
  const hasPos = d1 > 0 || d2 > 0 || d3 > 0;
  return !(hasNeg && hasPos);
}

function fillTriangle(ax, ay, bx, by, cx, cy, r, g, b) {
  const minX = Math.min(ax, bx, cx);
  const maxX = Math.max(ax, bx, cx);
  const minY = Math.min(ay, by, cy);
  const maxY = Math.max(ay, by, cy);
  for (let y = Math.floor(minY); y < Math.ceil(maxY); y++) {
    for (let x = Math.floor(minX); x < Math.ceil(maxX); x++) {
      if (pointInTriangle(x + 0.5, y + 0.5, ax, ay, bx, by, cx, cy)) {
        setPixel(x, y, r, g, b);
      }
    }
  }
}

// 背景：白色
fillRect(0, 0, SIZE, SIZE, 255, 255, 255);

// 品牌蓝 #3b6cff
const R = 0x3b, G = 0x6c, B = 0xff;

const cy = SIZE / 2;
const barH = 70; // 箭头杆厚度
// 中央连接横杆
fillRect(430, cy - barH / 2, 594, cy + barH / 2, R, G, B);
// 左臂杆（指向左）
fillRect(300, cy - barH / 2, 512, cy + barH / 2, R, G, B);
// 右臂杆（指向右）
fillRect(512, cy - barH / 2, 724, cy + barH / 2, R, G, B);
// 左箭头三角（尖端在 x=200）
fillTriangle(200, cy, 300, cy - 150, 300, cy + 150, R, G, B);
// 右箭头三角（尖端在 x=824）
fillTriangle(824, cy, 724, cy - 150, 724, cy + 150, R, G, B);

// ---- 最小 PNG 编码器（truecolor+alpha, 8bit） ----
function crc32(buf) {
  let c;
  const table = crc32.table || (crc32.table = (() => {
    const t = [];
    for (let n = 0; n < 256; n++) {
      c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      t[n] = c >>> 0;
    }
    return t;
  })());
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    crc = (crc >>> 8) ^ table[(crc ^ buf[i]) & 0xff];
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crcBuf]);
}

const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(SIZE, 0);
ihdr.writeUInt32BE(SIZE, 4);
ihdr[8] = 8; // bit depth
ihdr[9] = 6; // color type RGBA
ihdr[10] = 0;
ihdr[11] = 0;
ihdr[12] = 0;

// 每行加 filter byte 0
const raw = Buffer.alloc((SIZE * 4 + 1) * SIZE);
for (let y = 0; y < SIZE; y++) {
  const dst = y * (SIZE * 4 + 1);
  raw[dst] = 0;
  buf.copy(raw, dst + 1, y * SIZE * 4, (y + 1) * SIZE * 4);
}
const idat = zlib.deflateSync(raw);

const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr),
  chunk('IDAT', idat),
  chunk('IEND', Buffer.alloc(0)),
]);

fs.writeFileSync(outPath, png);
console.log('[icon] wrote', outPath, png.length, 'bytes');
