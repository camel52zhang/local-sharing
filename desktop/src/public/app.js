// 局域网互传工具 - 电脑端仪表盘（优化版）
const $ = (sel) => document.querySelector(sel);

let ws = null;
let picked = []; // { name, file, folder }
const DEVICE_ICON = { phone: '📱', tablet: '📲', pc: '💻', device: '📦' };
const KIND_ICON = { folder: '📁', file: '📄' };

// ---- 初始化 ----
async function initInfo() {
  const info = await fetch('/api/info').then((r) => r.json());
  $('#connectUrl').textContent = info.connectUrl;
  $('#devName').textContent = info.name || '本机';
  $('#qr').src = '/api/qr';
  $('#copyBtn').onclick = () => copyText(info.connectUrl);
  connectWs();
  loadActivity();
}

// ---- WebSocket ----
function connectWs() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(`${proto}://${location.host}/ws?role=pc`);
  ws.onopen = () => setConn(true);
  ws.onclose = () => { setConn(false); setTimeout(connectWs, 2000); };
  ws.onerror = () => setConn(false);
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.type === 'device-list') renderDevices(msg.devices);
    if (msg.type === 'upload-received') { toast(`收到 ${msg.files.length} 个文件`); loadActivity(); }
    if (msg.type === 'transfer-out-started') loadActivity();
    if (msg.type === 'transfer-completed') loadActivity();
    if (msg.type === 'transfer-failed') loadActivity();
  };
}

function setConn(on) {
  $('#connState').innerHTML = on
    ? '<span class="dot on"></span> 实时已连接'
    : '<span class="dot off"></span> 连接断开';
}

// ---- 设备列表 ----
function renderDevices(devices) {
  $('#devCount').textContent = devices.length;
  const ul = $('#deviceList');
  const sel = $('#targetDevice');
  if (!devices.length) {
    ul.innerHTML = '<li class="empty"><div class="ring">🔌</div><div class="t">还没有设备连接</div><div class="s">让手机扫描上方二维码即可连接</div></li>';
    sel.innerHTML = '<option value="">— 请选择 —</option>';
    return;
  }
  ul.innerHTML = devices.map((d) => `
    <li class="item">
      <span class="ico">${DEVICE_ICON[d.icon] || DEVICE_ICON.device}</span>
      <span class="name">${esc(d.name)}</span>
      <span class="pill ${d.online ? 'on' : 'off'}">${d.online ? '在线' : '离线'}</span>
    </li>`).join('');
  sel.innerHTML = '<option value="">— 请选择 —</option>' +
    devices.map((d) => `<option value="${d.id}">${esc(d.name)}</option>`).join('');
}

// ---- 传输记录（统一活动流） ----
async function loadActivity() {
  const { activities } = await fetch('/api/activity').then((r) => r.json());
  const ul = $('#transferLog');
  if (!activities.length) {
    ul.innerHTML = '<li class="empty"><div class="ring">📡</div><div class="t">还没有传输记录</div><div class="s">从手机发送文件，或在右侧发给手机，都会显示在这里</div></li>';
    return;
  }
  ul.innerHTML = activities.map((a) => {
    const dir = a.direction === 'in' ? '↓' : '↑';
    const statusPill = statusPillOf(a);
    const del = a.direction === 'in'
      ? `<button class="rm" data-del="${a.id}" title="删除">✕</button>` : '';
    return `<li>
      <div class="tl-row">
        <span class="tl-dir ${a.direction}">${dir}</span>
        <span class="tl-name">${esc(a.name)}</span>
        ${statusPill}
        ${del}
      </div>
      <span class="tl-meta">${esc(a.deviceName)} · ${fmtSize(a.size)}</span>
      <span class="tl-t">${fmtTime(a.time)}</span>
    </li>`;
  }).join('');
  ul.querySelectorAll('[data-del]').forEach((b) => {
    b.onclick = () => deleteReceived(b.getAttribute('data-del'));
  });
}

function statusPillOf(a) {
  if (a.direction === 'in') return '<span class="pill on">已接收</span>';
  switch (a.status) {
    case 'ready': return '<span class="pill warn">发送中</span>';
    case 'completed': return '<span class="pill on">已完成</span>';
    case 'failed': return '<span class="pill fail">失败</span>';
    default: return '<span class="pill off">待发送</span>';
  }
}

async function deleteReceived(id) {
  const res = await fetch(`/api/received/${id}`, { method: 'DELETE' });
  if (res.ok) { toast('已删除', true); loadActivity(); }
  else toast('删除失败', false);
}

$('#clearBtn').onclick = async () => {
  if (!confirm('确定清空所有收到的文件？此操作不可撤销。')) return;
  const res = await fetch('/api/received', { method: 'DELETE' });
  if (res.ok) { toast('已清空', true); loadActivity(); }
  else toast('清空失败', false);
};

// ---- 选择文件 ----
$('#fileInput').addEventListener('change', (e) => addPicked(e.target.files, false));
$('#folderInput').addEventListener('change', (e) => addPicked(e.target.files, true));

function addPicked(fileList, isFolder) {
  for (const f of fileList) picked.push({ name: f.name, file: f, folder: isFolder });
  renderPicked();
}
function renderPicked() {
  $('#pickList').innerHTML = picked.length
    ? picked.map((p, i) => `<li class="item"><span class="ico">${p.folder ? KIND_ICON.folder : KIND_ICON.file}</span><span class="name">${esc(p.name)}</span><span class="meta">${fmtSize(p.file.size)}</span><button data-i="${i}" class="rm">✕</button></li>`).join('')
    : '<li class="empty"><div class="ring">📂</div><div class="t">未选择任何文件</div><div class="s">点击上方按钮，或直接把文件拖到发送区</div></li>';
  $('#pickList').querySelectorAll('.rm').forEach((b) => b.onclick = () => { picked.splice(+b.dataset.i, 1); renderPicked(); });
  $('#sendBtn').disabled = !picked.length || !$('#targetDevice').value;
}
$('#targetDevice').addEventListener('change', renderPicked);

// ---- 拖拽 ----
const drop = $('#drop');
['dragenter', 'dragover'].forEach((ev) => drop.addEventListener(ev, (e) => {
  e.preventDefault(); drop.classList.add('over');
}));
['dragleave', 'drop'].forEach((ev) => drop.addEventListener(ev, (e) => {
  e.preventDefault(); drop.classList.remove('over');
}));
drop.addEventListener('drop', (e) => {
  const files = e.dataTransfer?.files;
  if (files && files.length) {
    // 含子目录时浏览器只给扁平文件，文件夹统一标记为 folder 由后端打包
    for (const f of files) picked.push({ name: f.name, file: f, folder: false });
    renderPicked();
  }
});

// ---- 发送 ----
$('#sendBtn').addEventListener('click', async () => {
  const deviceId = $('#targetDevice').value;
  if (!deviceId || !picked.length) return;
  const fd = new FormData();
  picked.forEach((p) => fd.append('files', p.file, p.name));
  fd.append('deviceId', deviceId);
  fd.append('asFolder', picked.some((p) => p.folder) ? '1' : '0');
  $('#sendBtn').disabled = true;
  try {
    const res = await fetch('/api/transfer/out', { method: 'POST', body: fd });
    const data = await res.json();
    if (data.ok) log(`已发送：${data.transfer.name} → ${$('#targetDevice').selectedOptions[0].text}`);
    else log('发送失败：' + (data.error || '未知错误'));
  } catch (e) {
    log('发送失败：' + e.message);
  }
  picked = []; renderPicked();
});

function log(msg) {
  const el = document.createElement('div');
  el.textContent = '• ' + msg;
  $('#sendLog').prepend(el);
}

// ---- 工具 ----
function fmtSize(b) {
  if (b < 1024) return b + ' B';
  if (b < 1048576) return (b / 1024).toFixed(1) + ' KB';
  return (b / 1048576).toFixed(1) + ' MB';
}
function fmtTime(ts) {
  const d = new Date(ts);
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function copyText(text) {
  navigator.clipboard?.writeText(text).then(
    () => toast('地址已复制', true),
    () => toast('复制失败', false),
  );
}
function toast(msg, ok = true) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast show ' + (ok ? 'ok' : 'err');
  setTimeout(() => t.classList.remove('show'), 2600);
}

initInfo();
