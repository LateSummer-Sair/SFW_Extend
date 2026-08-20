// ==================== SFW 文件管理器 ====================
const FM = {
    currentPath: '',
    viewMode: 'list', // 'list' | 'grid'
    selectedFile: null,
    fileCache: {},
};

// 初始化文件管理器
function initFileManager() {
    fetchFileRoot().then(root => {
        FM.currentPath = '';
        loadFileList('');
    });
}

async function fetchFileRoot() {
    try {
        const r = await fetch('/api/file/root');
        return (await r.json()).root;
    } catch (e) { return ''; }
}

// 进入文件管理器
function enterFileManager() {
    const mainScreen = document.getElementById('mainScreen');
    const fmScreen = document.getElementById('fmScreen');
    mainScreen.classList.remove('active');
    fmScreen.classList.add('active');
    if (_particleSys) _particleSys.setActive(false);
    if (!FM.currentPath) {
        initFileManager();
    }
}

// 退出文件管理器
function exitFileManager() {
    document.getElementById('fmScreen').classList.remove('active');
    document.getElementById('mainScreen').classList.add('active');
    document.getElementById('commandInput').focus();
    // 关闭编辑器、预览器等浮层
    closeAllFmOverlays();
}

function closeAllFmOverlays() {
    document.getElementById('fmEditor').classList.remove('active');
    document.getElementById('fmPreview').classList.remove('active');
    document.getElementById('fmRenameDialog').classList.remove('active');
    document.getElementById('fmCreateDialog').classList.remove('active');
}

// ==================== 文件列表加载 ====================

async function loadFileList(path) {
    FM.currentPath = path;
    try {
        const r = await fetch('/api/file/list?path=' + encodeURIComponent(path));
        if (!r.ok) { showFmError('加载失败: ' + r.status); return; }
        const files = await r.json();
        renderFileList(files);
        renderBreadcrumb(path);
        updateFmToolbar(path);
    } catch (e) {
        showFmError('网络错误: ' + e.message);
    }
}

function renderBreadcrumb(path) {
    const bc = document.getElementById('fmBreadcrumb');
    let html = '<span class="fm-crumb" onclick="loadFileList(\'\')">📁 SFW</span>';
    if (path) {
        const parts = path.split('/').filter(Boolean);
        let acc = '';
        for (let i = 0; i < parts.length; i++) {
            acc += (acc ? '/' : '') + parts[i];
            html += '<span class="fm-crumb-sep">/</span>';
            html += '<span class="fm-crumb" onclick="loadFileList(\'' + escAttr(acc) + '\')">' + escHtml(parts[i]) + '</span>';
        }
    }
    bc.innerHTML = html;
}

function updateFmToolbar(path) {
    const backBtn = document.getElementById('fmBackBtn');
    backBtn.style.visibility = path ? 'visible' : 'hidden';
}

function goBack() {
    if (!FM.currentPath) return;
    const parts = FM.currentPath.split('/');
    parts.pop();
    loadFileList(parts.join('/'));
}

function renderFileList(files) {
    const container = document.getElementById('fmFileList');
    if (!files.length) {
        container.innerHTML = '<div class="fm-empty">📭 此目录为空</div>';
        return;
    }
    const isGrid = FM.viewMode === 'grid';
    container.className = isGrid ? 'fm-file-grid' : 'fm-file-list';

    let html = '';
    for (const f of files) {
        if (isGrid) {
            html += renderGridItem(f);
        } else {
            html += renderListItem(f);
        }
    }
    container.innerHTML = html;
}

function renderListItem(f) {
    const icon = getFileIcon(f);
    const sizeStr = f.isDir ? '' : formatSize(f.size);
    const dateStr = new Date(f.lastModified).toLocaleString();
    const cls = f.isDir ? 'fm-item-dir' : 'fm-item-file';

    return `<div class="fm-list-item ${cls}" onclick="onFileClick(event, this)" data-path="${escAttr(f.path)}" data-isdir="${f.isDir}" data-name="${escAttr(f.name)}">
        <span class="fm-item-icon">${icon}</span>
        <span class="fm-item-name">${escHtml(f.name)}</span>
        <span class="fm-item-size">${sizeStr}</span>
        <span class="fm-item-date">${dateStr}</span>
        <span class="fm-item-actions">
            ${f.isText ? '<button class="fm-btn-sm" onclick="event.stopPropagation();openFileEditor(\'' + escAttr(f.path) + '\',\'' + escAttr(f.name) + '\')" title="编辑">✏️</button>' : ''}
            ${f.isIr ? '<button class="fm-btn-sm" onclick="event.stopPropagation();executeIrFile(\'' + escAttr(f.path) + '\',\'' + escAttr(f.name) + '\')" title="执行IR">▶️</button>' : ''}
            ${f.isImage||f.isMedia||f.isAudio ? '<button class="fm-btn-sm" onclick="event.stopPropagation();previewFile(\'' + escAttr(f.path) + '\',\'' + escAttr(f.name) + '\',\'' + (f.isImage?'image':f.isMedia?'media':'audio') + '\')" title="预览">👁️</button>' : ''}
            <button class="fm-btn-sm" onclick="event.stopPropagation();renameFileDialog(\'' + escAttr(f.path) + '\',\'' + escAttr(f.name) + '\')" title="重命名">📝</button>
            <button class="fm-btn-sm fm-btn-danger" onclick="event.stopPropagation();deleteFileConfirm(\'' + escAttr(f.path) + '\',\'' + escAttr(f.name) + '\',${f.isDir})" title="删除">🗑️</button>
        </span>
    </div>`;
}

function renderGridItem(f) {
    const icon = getFileIcon(f);
    const cls = f.isDir ? 'fm-item-dir' : 'fm-item-file';
    let preview = '';

    if (f.isImage) {
        preview = `<div class="fm-grid-preview"><img src="/api/file/preview?path=${encodeURIComponent(f.path)}" loading="lazy" alt=""></div>`;
    } else if (f.isMedia || f.isAudio) {
        preview = `<div class="fm-grid-preview fm-grid-media-icon">${f.isAudio ? '🎵' : '🎬'}</div>`;
    } else if (!f.isDir) {
        preview = `<div class="fm-grid-preview fm-grid-file-icon">${icon}</div>`;
    }

    return `<div class="fm-grid-item ${cls}" onclick="onFileClick(event, this)" data-path="${escAttr(f.path)}" data-isdir="${f.isDir}" data-name="${escAttr(f.name)}">
        ${preview}
        <span class="fm-grid-name">${escHtml(f.name)}</span>
        ${!f.isDir ? '<span class="fm-grid-size">' + formatSize(f.size) + '</span>' : ''}
        <span class="fm-item-actions fm-grid-actions">
            ${f.isImage||f.isMedia||f.isAudio ? '<button class="fm-btn-sm" onclick="event.stopPropagation();previewFile(\'' + escAttr(f.path) + '\',\'' + escAttr(f.name) + '\',\'' + (f.isImage?'image':f.isMedia?'media':'audio') + '\')" title="预览">👁️</button>' : ''}
            ${f.isText ? '<button class="fm-btn-sm" onclick="event.stopPropagation();openFileEditor(\'' + escAttr(f.path) + '\',\'' + escAttr(f.name) + '\')" title="编辑">✏️</button>' : ''}
        </span>
    </div>`;
}

function onFileClick(e, el) {
    const path = el.dataset.path;
    const isDir = el.dataset.isdir === 'true';
    if (isDir) {
        loadFileList(path);
    } else {
        // 单击选中，双击打开
        FM.selectedFile = path;
        highlightSelected(el);
    }
}

function highlightSelected(el) {
    document.querySelectorAll('.fm-list-item.selected, .fm-grid-item.selected').forEach(e => e.classList.remove('selected'));
    if (el) el.classList.add('selected');
}

// 双击文件处理
document.addEventListener('dblclick', function(e) {
    const item = e.target.closest('.fm-list-item, .fm-grid-item');
    if (!item) return;
    const path = item.dataset.path;
    const isDir = item.dataset.isdir === 'true';
    const name = item.dataset.name;
    if (isDir) return;
    // 判断文件类型并打开相应操作
    if (isImageFile(name)) {
        previewFile(path, name, 'image');
    } else if (isMediaFile(name)) {
        previewFile(path, name, 'media');
    } else if (isAudioFile(name)) {
        previewFile(path, name, 'audio');
    } else if (name.endsWith('.ir')) {
        openFileEditor(path, name);
    } else if (isTextFile(name)) {
        openFileEditor(path, name);
    } else {
        showFmToast('无法预览此文件类型', 'error');
    }
});

// ==================== 文件操作 ====================

// 预览文件（图片/媒体/音频）
function previewFile(path, name, type) {
    const preview = document.getElementById('fmPreview');
    const content = document.getElementById('fmPreviewContent');
    const title = document.getElementById('fmPreviewTitle');
    const url = '/api/file/preview?path=' + encodeURIComponent(path);

    let html = '';
    if (type === 'image') {
        html = `<img src="${url}" alt="${escHtml(name)}" style="max-width:100%;max-height:100%;object-fit:contain;">`;
    } else if (type === 'media') {
        html = `<video controls style="max-width:100%;max-height:100%;" src="${url}"></video>`;
    } else if (type === 'audio') {
        html = `<div style="text-align:center;padding:40px;">
            <div style="font-size:64px;margin-bottom:20px;">🎵</div>
            <div style="color:var(--font-color);margin-bottom:20px;">${escHtml(name)}</div>
            <audio controls style="width:100%;max-width:400px;" src="${url}"></audio>
        </div>`;
    }
    content.innerHTML = html;
    title.textContent = name;
    preview.classList.add('active');
}

function closeFmPreview() {
    document.getElementById('fmPreview').classList.remove('active');
    const audio = document.querySelector('#fmPreviewContent audio, #fmPreviewContent video');
    if (audio) audio.pause();
}

// 编辑文件
async function openFileEditor(path, name) {
    const editor = document.getElementById('fmEditor');
    document.getElementById('fmEditorTitle').textContent = name;
    document.getElementById('fmEditorPath').value = path;

    const textarea = document.getElementById('fmEditorTextarea');
    textarea.value = '加载中...';
    textarea.disabled = true;

    editor.classList.add('active');

    try {
        const r = await fetch('/api/file/read?path=' + encodeURIComponent(path));
        const d = await r.json();
        if (d.content !== undefined) {
            textarea.value = d.content;
            textarea.disabled = false;
            // 判断是否需要语法高亮
            const ext = name.split('.').pop().toLowerCase();
            const mode = getCodeMirrorMode(ext);
            if (mode) {
                textarea.className = 'fm-editor-textarea fm-code-highlight';
                textarea.spellcheck = false;
            } else {
                textarea.className = 'fm-editor-textarea';
                textarea.spellcheck = true;
            }
            // IR文件特殊标记
            if (ext === 'ir') {
                textarea.className = 'fm-editor-textarea fm-code-highlight fm-ir-highlight';
                document.getElementById('fmIrRunBtn').style.display = '';
            } else {
                document.getElementById('fmIrRunBtn').style.display = 'none';
            }
        } else {
            textarea.value = d.error || '加载失败';
        }
    } catch (e) {
        textarea.value = '加载失败: ' + e.message;
    }
}

function closeFmEditor() {
    document.getElementById('fmEditor').classList.remove('active');
}

async function saveFile() {
    const path = document.getElementById('fmEditorPath').value;
    const content = document.getElementById('fmEditorTextarea').value;

    try {
        const r = await fetch('/api/file/write', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: path, content: content })
        });
        const d = await r.json();
        if (d.success) {
            showFmToast('保存成功', 'success');
            closeFmEditor();
            loadFileList(FM.currentPath);
        } else {
            showFmToast(d.error || '保存失败', 'error');
        }
    } catch (e) {
        showFmToast('保存失败: ' + e.message, 'error');
    }
}

// 执行 IR 文件
async function executeIrFile(path, name) {
    if (!confirm('确定要执行 IR 脚本 "' + name + '" 吗？\n\nIR 脚本将在 SFW 控制台中执行。')) return;

    try {
        const r = await fetch('/api/file/ir-execute', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: path })
        });
        const d = await r.json();
        if (d.success) {
            showFmToast('IR 脚本已提交执行: ' + name, 'success');
            // 切换到控制台查看执行结果
            exitFileManager();
        } else {
            showFmToast(d.error || '执行失败', 'error');
        }
    } catch (e) {
        showFmToast('执行失败: ' + e.message, 'error');
    }
}

// 重命名/移动
function renameFileDialog(path, name) {
    const dialog = document.getElementById('fmRenameDialog');
    document.getElementById('fmRenameOldPath').value = path;
    document.getElementById('fmRenameNewName').value = name;
    document.getElementById('fmRenameNewName').focus();
    document.getElementById('fmRenameNewName').select();
    dialog.classList.add('active');
}

function closeFmRename() {
    document.getElementById('fmRenameDialog').classList.remove('active');
}

async function doRename() {
    const oldPath = document.getElementById('fmRenameOldPath').value;
    const newName = document.getElementById('fmRenameNewName').value.trim();
    if (!newName) { showFmToast('请输入新名称', 'error'); return; }

    // 构建新路径
    const parts = oldPath.split('/');
    parts.pop();
    const dir = parts.length ? parts.join('/') + '/' : '';
    const newPath = dir + newName;

    try {
        const r = await fetch('/api/file/move', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ oldPath: oldPath, newPath: newPath })
        });
        const d = await r.json();
        if (d.success) {
            closeFmRename();
            showFmToast('重命名成功', 'success');
            loadFileList(FM.currentPath);
        } else {
            showFmToast(d.error || '重命名失败', 'error');
        }
    } catch (e) {
        showFmToast('重命名失败: ' + e.message, 'error');
    }
}

// 删除确认
function deleteFileConfirm(path, name, isDir) {
    if (!confirm('确定要删除 ' + (isDir ? '目录' : '文件') + ' "' + name + '" 吗？\n\n此操作不可撤销！')) return;

    fetch('/api/file/delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: path })
    }).then(r => r.json()).then(d => {
        if (d.success) {
            showFmToast('已删除: ' + name, 'success');
            loadFileList(FM.currentPath);
        } else {
            showFmToast(d.error || '删除失败', 'error');
        }
    }).catch(e => {
        showFmToast('删除失败: ' + e.message, 'error');
    });
}

// 新建文件/目录
function createFileDialog() {
    document.getElementById('fmCreateDialog').classList.add('active');
    document.getElementById('fmCreateName').value = '';
    document.getElementById('fmCreateName').focus();
}

function closeFmCreate() {
    document.getElementById('fmCreateDialog').classList.remove('active');
}

async function doCreate() {
    const name = document.getElementById('fmCreateName').value.trim();
    const isDir = document.getElementById('fmCreateIsDir').checked;
    if (!name) { showFmToast('请输入名称', 'error'); return; }

    const path = FM.currentPath ? FM.currentPath + '/' + name : name;

    try {
        const r = await fetch('/api/file/create', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: path, isDir: isDir ? 'true' : 'false' })
        });
        const d = await r.json();
        if (d.success) {
            closeFmCreate();
            showFmToast('已创建: ' + name, 'success');
            loadFileList(FM.currentPath);
        } else {
            showFmToast(d.error || '创建失败', 'error');
        }
    } catch (e) {
        showFmToast('创建失败: ' + e.message, 'error');
    }
}

// 上传文件
function uploadFileDialog() {
    document.getElementById('fmUploadInput').click();
}

function onUploadFile(input) {
    const file = input.files[0];
    if (!file) return;
    if (file.size > 50 * 1024 * 1024) {
        showFmToast('文件过大（最大50MB）', 'error');
        input.value = '';
        return;
    }

    const formData = new FormData();
    formData.append('file', file);

    const url = '/api/file/upload?dirPath=' + encodeURIComponent(FM.currentPath);

    showFmToast('正在上传: ' + file.name + '...', 'success');

    fetch(url, {
        method: 'POST',
        body: formData
    }).then(r => r.json()).then(d => {
        input.value = '';
        if (d.success) {
            showFmToast('上传成功: ' + (d.name || file.name), 'success');
            loadFileList(FM.currentPath);
        } else {
            showFmToast(d.error || '上传失败', 'error');
        }
    }).catch(e => {
        showFmToast('上传失败: ' + e.message, 'error');
        input.value = '';
    });
}

// ==================== 切换视图 ====================

function setFmViewMode(mode) {
    FM.viewMode = mode;
    document.getElementById('fmViewList').classList.toggle('active', mode === 'list');
    document.getElementById('fmViewGrid').classList.toggle('active', mode === 'grid');
    // 重新渲染
    if (FM.currentPath !== undefined) {
        fetch('/api/file/list?path=' + encodeURIComponent(FM.currentPath))
            .then(r => r.json()).then(renderFileList)
            .catch(() => {});
    }
}

// ==================== 工具函数 ====================

function getFileIcon(f) {
    if (f.isDir) return '📁';
    if (f.isImage) return '🖼️';
    if (f.isAudio) return '🎵';
    if (f.isMedia) return '🎬';
    if (f.isIr) return '⚡';
    if (f.isCode) return '📝';
    if (f.isText) return '📄';
    const ext = f.extension;
    if (ext === 'zip' || ext === 'jar' || ext === 'rar' || ext === '7z') return '📦';
    if (ext === 'pdf') return '📕';
    return '📎';
}

function isImageFile(name) {
    const n = name.toLowerCase();
    return /\.(png|jpg|jpeg|gif|bmp|svg|webp|ico|tiff?)$/.test(n);
}

function isMediaFile(name) {
    const n = name.toLowerCase();
    return /\.(mp4|webm|mkv|avi|mov|wmv|flv|m4v)$/.test(n);
}

function isAudioFile(name) {
    const n = name.toLowerCase();
    return /\.(mp3|wav|ogg|flac|aac|m4a|wma|opus)$/.test(n);
}

function isTextFile(name) {
    const n = name.toLowerCase();
    return /\.(txt|log|cfg|ini|conf|properties|csv|md|ir|js|java|cpp|c|h|py|css|html|xml|json|ts|jsx|tsx|vue|php|rb|go|rs|swift|kt|scala|sh|bat|ps1|sql|yaml|yml|cs|lua|r|tex|bib|toml|env|editorconfig|gitignore)$/.test(n)
        || (n.indexOf('.') === -1);
}

function getCodeMirrorMode(ext) {
    const map = {
        js:'javascript', ts:'javascript', jsx:'javascript', tsx:'javascript',
        java:'text/x-java', py:'python', cpp:'text/x-c++src', c:'text/x-csrc',
        h:'text/x-c++hdr', css:'css', html:'htmlmixed', xml:'xml',
        json:'application/json', sql:'text/x-sql', sh:'shell', bat:'shell',
        php:'application/x-httpd-php', rb:'ruby', go:'go', rs:'rust',
        swift:'swift', kt:'text/x-kotlin', cs:'text/x-csharp',
        yaml:'yaml', yml:'yaml', lua:'lua', r:'text/x-rsrc',
        md:'markdown', ir:'text/x-ir'
    };
    return map[ext] || null;
}

function formatSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

function escHtml(s) {
    if (!s) return '';
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function escAttr(s) {
    if (!s) return '';
    return s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/'/g, '&#39;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function showFmToast(msg, type) {
    const t = document.getElementById('toast');
    t.textContent = msg;
    t.className = 'toast ' + (type === 'error' ? 'error' : '');
    t.classList.add('show');
    clearTimeout(t._to);
    t._to = setTimeout(() => t.classList.remove('show'), 3000);
}

function showFmError(msg) {
    showFmToast(msg, 'error');
}

// 键盘快捷键支持
document.addEventListener('keydown', function(e) {
    const fmScreen = document.getElementById('fmScreen');
    if (!fmScreen.classList.contains('active')) return;

    if (e.key === 'Escape') {
        if (document.getElementById('fmEditor').classList.contains('active')) {
            closeFmEditor();
        } else if (document.getElementById('fmPreview').classList.contains('active')) {
            closeFmPreview();
        } else if (document.getElementById('fmRenameDialog').classList.contains('active')) {
            closeFmRename();
        } else if (document.getElementById('fmCreateDialog').classList.contains('active')) {
            closeFmCreate();
        } else {
            exitFileManager();
        }
        e.preventDefault();
        return;
    }

    // 保存快捷键
    if ((e.ctrlKey || e.metaKey) && e.key === 's' && document.getElementById('fmEditor').classList.contains('active')) {
        e.preventDefault();
        saveFile();
        return;
    }

    // 退格键返回上级目录
    if (e.key === 'Backspace' && document.activeElement === document.body) {
        if (FM.currentPath) {
            goBack();
            e.preventDefault();
        }
    }
});

