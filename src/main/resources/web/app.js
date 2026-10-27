'use strict';

const form = document.getElementById('download-form');
const urlInput = document.getElementById('source-url');
const submitButton = document.getElementById('download-button');
const result = document.getElementById('result');
const saveAgain = document.getElementById('save-again');
let configuration;
let downloadObjectUrl;
let busy = false;

function sourceType() {
  return form.elements.sourceType.value;
}

function refreshMode() {
  const type = sourceType();
  const clawhub = type === 'clawhub';
  document.getElementById('github-options').hidden = clawhub;
  document.getElementById('clawhub-options').hidden = !clawhub;
  const notes = {
    github: 'GitHub：先下载仓库，再提取单个 Skill。仓库含多个技能时，请在高级选项中指定目录或名称。',
    'skills.sh': configuration?.skillsShMode === 'api'
      ? '正式 API 模式：由服务器获取详情，再下载 GitHub 内容或打包平台文件。需要服务端有效凭证。'
      : '快捷模式：从 skills.sh 链接定位 GitHub 仓库，再从 GitHub 下载。此模式不调用 skills.sh 正式 API。',
    clawhub: 'ClawHub：按页面作者和技能标识获取内容；平台 ZIP 直接校验，GitHub 来源复用仓库下载。'
  };
  document.getElementById('mode-note').textContent = notes[type];
  urlInput.placeholder = {
    github: 'https://github.com/owner/repo',
    'skills.sh': 'https://skills.sh/owner/repo/skill',
    clawhub: 'https://clawhub.ai/owner/skills/slug'
  }[type];
}

function status(kind, title, message) {
  result.hidden = false;
  result.className = 'result ' + kind;
  document.getElementById('result-title').textContent = title;
  document.getElementById('result-message').textContent = message;
  document.getElementById('result-hash').hidden = true;
  saveAgain.hidden = true;
}

for (const radio of form.elements.sourceType) radio.addEventListener('change', refreshMode);

document.getElementById('fill-example').addEventListener('click', () => {
  if (busy) return;
  const type = sourceType();
  urlInput.value = {
    github: 'https://github.com/vercel-labs/skills',
    'skills.sh': 'https://skills.sh/vercel-labs/skills/find-skills',
    clawhub: 'https://clawhub.ai/steipete/skills/weather'
  }[type];
  for (const field of ['skillPath', 'skill', 'ref', 'version']) form.elements[field].value = '';
  if (type === 'github') {
    form.elements.skill.value = 'find-skills';
    document.getElementById('advanced').open = true;
  }
  urlInput.focus();
});

form.addEventListener('submit', async event => {
  event.preventDefault();
  if (busy || !configuration || !form.reportValidity()) return;
  const type = sourceType();
  let url;
  try { url = new URL(urlInput.value.trim()); }
  catch { status('error', '请检查链接', '请输入完整的 HTTPS 技能页面链接。'); return; }
  const hosts = { github: ['github.com'], 'skills.sh': ['skills.sh', 'www.skills.sh'], clawhub: ['clawhub.ai', 'clawhub.com'] };
  if (url.protocol !== 'https:' || !hosts[type].includes(url.hostname) || url.username || url.password || url.port) {
    status('error', '来源与链接不匹配', '请选择与链接一致的来源，且仅使用标准 HTTPS 页面链接。');
    return;
  }
  const payload = { sourceType: type, sourceUrl: urlInput.value.trim() };
  const fields = type === 'clawhub' ? ['version'] : ['skillPath', 'skill', 'ref'];
  for (const field of fields) {
    const value = form.elements[field].value.trim();
    if (value) payload[field] = value;
  }
  busy = true;
  submitButton.disabled = true;
  form.setAttribute('aria-busy', 'true');
  document.getElementById('button-label').textContent = '正在生成 ZIP…';
  status('pending', '正在下载并校验', '平台请求和仓库打包可能需要一些时间，请不要重复提交。');
  if (downloadObjectUrl) { URL.revokeObjectURL(downloadObjectUrl); downloadObjectUrl = undefined; }
  try {
    const response = await fetch('/api/download', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': configuration.csrfToken },
      body: JSON.stringify(payload)
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => ({}));
      throw new Error(problem.message || `请求失败（HTTP ${response.status}），请稍后重试。`);
    }
    if (!response.headers.get('Content-Type')?.startsWith('application/zip')) throw new Error('服务器未返回 ZIP 文件。');
    const blob = await response.blob();
    if (!blob.size) throw new Error('服务器返回了空文件。');
    const disposition = response.headers.get('Content-Disposition') || '';
    const filename = /filename="([a-zA-Z0-9._-]+)"/.exec(disposition)?.[1] || 'skill.zip';
    downloadObjectUrl = URL.createObjectURL(blob);
    saveAgain.href = downloadObjectUrl;
    saveAgain.download = filename;
    status('success', 'ZIP 已准备好', `${filename} · ${(blob.size / 1024).toFixed(1)} KiB。已向浏览器发起保存；如果未弹出，请点击下方再次保存。`);
    const hash = response.headers.get('X-Skill-SHA256');
    if (hash && /^[0-9a-f]{64}$/.test(hash)) {
      document.getElementById('result-hash').textContent = 'SHA-256: ' + hash;
      document.getElementById('result-hash').hidden = false;
    }
    saveAgain.hidden = false;
    saveAgain.click();
  } catch (error) {
    status('error', '这次没有下载成功', error instanceof TypeError ? '无法连接本机服务，或传输中断。请确认程序仍在运行。' : error.message);
  } finally {
    busy = false;
    submitButton.disabled = false;
    form.removeAttribute('aria-busy');
    document.getElementById('button-label').textContent = '下载 ZIP';
  }
});

async function initialize() {
  try {
    const response = await fetch('/api/config');
    if (!response.ok) throw new Error('无法读取本机服务配置。');
    configuration = await response.json();
    if (!configuration.csrfToken) throw new Error('服务配置无效，请重启程序。');
    refreshMode();
    submitButton.disabled = false;
  } catch (error) {
    status('error', '无法初始化页面', error.message + ' 请刷新页面，确认不是直接打开 HTML 文件。');
  }
}

initialize();
