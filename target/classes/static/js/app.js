const dropzone = document.getElementById('dropzone');
const fileInput = document.getElementById('fileInput');
const uploadBtn = document.getElementById('uploadBtn');
const uploadError = document.getElementById('uploadError');
const fileList = document.getElementById('fileList');
const strategyToggle = document.getElementById('strategyToggle');
const multimodalCheck = document.getElementById('multimodalCheck');

const questionInput = document.getElementById('questionInput');
const askBtn = document.getElementById('askBtn');
const askError = document.getElementById('askError');
const answerBlock = document.getElementById('answerBlock');
const answerText = document.getElementById('answerText');
const evidenceLabel = document.getElementById('evidenceLabel');
const evidenceList = document.getElementById('evidenceList');

const historyContainer = document.getElementById('historyContainer');
const emptyHistory = document.getElementById('emptyHistory');

let selectedFile = null;
const conversationId = crypto.randomUUID();

function isImage(filename) {
    const lower = filename.toLowerCase();
    return lower.endsWith('.png') || lower.endsWith('.jpg') || lower.endsWith('.jpeg');
}

function formatTime() {
    return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function evidenceCardHtml(sourceName, rank) {
    return `
    <div class="evidence-card">
      <span class="evidence-rank">#${rank}</span>
      <span class="evidence-file">${sourceName}</span>
    </div>
  `;
}

function setSelectedFile(file) {
    selectedFile = file;
    dropzone.querySelector('.main').textContent = file.name;
    strategyToggle.classList.toggle('show', isImage(file.name));
    uploadBtn.disabled = false;
    uploadError.textContent = '';
}

function resetUploadForm() {
    selectedFile = null;
    fileInput.value = '';
    dropzone.querySelector('.main').textContent = 'Drop a file, or click to browse';
    strategyToggle.classList.remove('show');
    multimodalCheck.checked = false;
}

function addFileChip(name, strategy) {
    const chip = document.createElement('div');
    chip.className = 'file-chip';
    chip.innerHTML = `<span class="name">${name}</span><span class="badge">${strategy}</span>`;
    fileList.prepend(chip);
}

dropzone.addEventListener('click', () => fileInput.click());
dropzone.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') fileInput.click();
});
fileInput.addEventListener('change', () => {
    if (fileInput.files.length) setSelectedFile(fileInput.files[0]);
});

['dragover', 'dragenter'].forEach(evt =>
    dropzone.addEventListener(evt, (e) => {
        e.preventDefault();
        dropzone.classList.add('drag');
    })
);
['dragleave', 'drop'].forEach(evt =>
    dropzone.addEventListener(evt, (e) => {
        e.preventDefault();
        dropzone.classList.remove('drag');
    })
);
dropzone.addEventListener('drop', (e) => {
    if (e.dataTransfer.files.length) setSelectedFile(e.dataTransfer.files[0]);
});

uploadBtn.addEventListener('click', async () => {
    if (!selectedFile) return;

    uploadBtn.disabled = true;
    uploadBtn.innerHTML = '<span class="spinner"></span> Uploading';
    uploadError.textContent = '';

    const useMultimodal = isImage(selectedFile.name) && multimodalCheck.checked;
    const endpoint = useMultimodal ? '/api/documents/multimodal-image' : '/api/documents';

    const formData = new FormData();
    formData.append('file', selectedFile);

    try {
        const res = await fetch(endpoint, { method: 'POST', body: formData });
        const data = await res.json();
        if (!res.ok) throw new Error(data.error || 'Upload failed.');

        const strategyLabel = useMultimodal ? 'multimodal' : (isImage(selectedFile.name) ? 'OCR' : 'text');
        addFileChip(selectedFile.name, strategyLabel);
        resetUploadForm();
    } catch (err) {
        uploadError.textContent = err.message;
    } finally {
        uploadBtn.disabled = true;
        uploadBtn.textContent = 'Upload';
    }
});

askBtn.addEventListener('click', async () => {
    const question = questionInput.value.trim();
    if (!question) return;

    askBtn.disabled = true;
    askBtn.innerHTML = '<span class="spinner"></span> Thinking';
    askError.textContent = '';
    answerBlock.classList.remove('show');

    try {
        const res = await fetch('/api/query', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ question, conversationId })
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.error || 'Something went wrong answering that.');

        renderAnswer(data.answer, data.sources || []);
        addHistoryEntry(question, data.answer, data.sources || []);
        questionInput.value = '';
    } catch (err) {
        askError.textContent = err.message;
    } finally {
        askBtn.disabled = false;
        askBtn.textContent = 'Ask';
    }
});

questionInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) askBtn.click();
});

function renderAnswer(answer, sources) {
    answerText.textContent = answer;
    evidenceLabel.textContent = sources.length ? 'Sources' : '';
    evidenceList.innerHTML = sources.map((s, i) => evidenceCardHtml(s, i + 1)).join('');
    answerBlock.classList.add('show');
}

function addHistoryEntry(question, answer, sources) {
    emptyHistory.style.display = 'none';

    const sourcesHtml = sources.length
        ? sources.map((s, i) => evidenceCardHtml(s, i + 1)).join('')
        : '<p style="font-size:13px;color:var(--ink-faint);">No sources — nothing relevant found.</p>';

    const item = document.createElement('div');
    item.className = 'history-item';
    item.innerHTML = `
    <div class="history-head">
      <span class="history-question">${question}</span>
      <span class="history-time">${formatTime()}</span>
    </div>
    <div class="history-body">
      <div class="answer-text" style="margin-bottom:10px;">${answer}</div>
      ${sourcesHtml}
    </div>
  `;

    item.querySelector('.history-head').addEventListener('click', () => {
        item.querySelector('.history-body').classList.toggle('show');
    });

    historyContainer.prepend(item);
}