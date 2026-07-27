// 局域网互传工具 - 电脑端仪表盘
const $ = (sel) => document.querySelector(sel);

let ws = null;
let picked = []; // { name, file }

// ---- 初始化信息 ----
async function initInfo() {
  const info = await fetch('/api/info').then((r) => r.json());
  $('#connectUrl').textContent = info.connectUrl;
  $('#devName').textContent = info.name;
  $('#qr').src = '/api/qr';
  connectWs();
  loadReceived();
}

// ---- WebSocket（电脑端仪表盘） ----
function connectWs() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(`${proto}://${location.host}/ws?role=pc`);
  ws.onopen = () => setConn(true);
  ws.onclose = () => { setConn(false); setTimeout(connectWs, 2000); };
  ws.onerror = () => setConn(false);
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.type === 'device-list') renderDevices(msg.devices);
    if (msg.type === 'upload-received') { toast(`收到文件：${msg.file.name}`); loadReceived(); };
    if (msg.type === 'transfer-completed') log(`传输完成：${msg.transferId}`);
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
    ul.innerHTML = '<li class="empty">暂无设备连接</li>';
    sel.innerHTML = '<option value="">— 请选择 —</option>';
    return;
  }
  ul.innerHTML = devices.map((d) => `
    <li class="item">
      <span class="name">${esc(d.name)}</span>
      <span class="pill ${d.online ? 'on' : 'off'}">${d.online ? '在线' : '离线'}</span>
    </li>`).join('');
  sel.innerHTML = '<option value="">— 请选择 —</option>' +
    devices.map((d) => `<option value="${d.id}">${esc(d.name)}</option>`).join('');
}

// ---- 收到的文件 ----
async function loadReceived() {
  const { files } = await fetch('/api/received').then((r) => r.json());
  const ul = $('#receivedList');
  if (!files.length) { ul.innerHTML = '<li class="empty">还没有收到文件</li>'; return; }
  ul.innerHTML = files.map((f) => `
    <li class="item">
      <span class="pill ${f.kind === 'folder' ? 'folder' : ''}">${f.kind === 'folder' ? '文件夹' : '文件'}</span>
      <span class="name">${esc(f.name)}</span>
      <span class="meta">${esc(f.fromDeviceName)} · ${fmtSize(f.size)}</span>
    </li>`).join('');
}

// ---- 选择文件 ----
$('#fileInput').addEventListener('change', (e) => addPicked(e.target.files, false));
$('#folderInput').addEventListener('change', (e) => addPicked(e.target.files, true));

function addPicked(fileList, isFolder) {
  for (const f of fileList) picked.push({ name: f.name, file: f, folder: isFolder });
  renderPicked();
}
function renderPicked() {
  $('#pickList').innerHTML = picked.length
    ? picked.map((p, i) => `<li class="item"><span class="name">${esc(p.name)}</span><span class="meta">${fmtSize(p.file.size)}</span><span class="pill ${p.folder ? 'folder' : ''}">${p.folder ? '夹' : '文件'}</span><button data-i="${i}" class="rm">✕</button></li>`).join('')
    : '<li class="empty">未选择任何文件</li>';
  $('#pickList').querySelectorAll('.rm').forEach((b) => b.onclick = () => { picked.splice(+b.dataset.i, 1); renderPicked(); });
  $('#sendBtn').disabled = !picked.length || !$('#targetDevice').value;
}
$('#targetDevice').addEventListener('change', renderPicked);

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
function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg; t.classList.add('show');
  setTimeout(() => t.classList.remove('show'), 2600);
}

initInfo();
