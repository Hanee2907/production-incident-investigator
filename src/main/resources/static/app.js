'use strict';

const $ = (sel) => document.querySelector(sel);
const STATUSES = ['OPEN', 'INVESTIGATING', 'RESOLVED', 'CLOSED'];
const SEVERITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

function esc(v) {
    return String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
const fmt = (ts) => (ts ? esc(ts.replace('T', ' ').slice(0, 19)) : '<span class="muted">–</span>');
const sev = (s) => `<span class="pill sev-${s}">${s}</span>`;
const tags = (list) => [...(list || [])].sort().map((s) => `<span class="tag">${esc(s)}</span>`).join('');
const short = (t) => (t ? esc(t.split('.').pop()) : '<span class="muted">–</span>');

async function api(path, options = {}) {
    const res = await fetch(path, options);
    if (!res.ok) {
        let detail = res.statusText;
        try { detail = (await res.json()).detail || detail; } catch (_) { /* not JSON */ }
        throw new Error(detail);
    }
    return res.status === 204 ? null : res.json();
}
const qs = (params) => new URLSearchParams(Object.entries(params).filter(([, v]) => v !== '' && v != null)).toString();

/* ---------- tabs ---------- */
const loaders = { overview: loadOverview, groups: loadGroups, incidents: loadIncidents, logs: loadLogs };
document.querySelectorAll('nav button').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));
function showTab(name) {
    document.querySelectorAll('nav button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.id === name));
    loaders[name]?.();
}

/* ---------- overview ---------- */
async function loadOverview() {
    const s = await api('/api/reports/summary');
    $('#window').textContent = `Log window: ${(s.from || '').replace('T', ' ')} → ${(s.to || '').replace('T', ' ')} UTC`;
    const active = s.incidentsByStatus.OPEN + s.incidentsByStatus.INVESTIGATING;
    const kpi = (v, l) => `<div class="kpi"><div class="v">${v}</div><div class="l">${l}</div></div>`;
    $('#kpis').innerHTML = [
        kpi(s.totalLogEntries, 'Log entries'),
        kpi(s.totalErrors, 'Errors'),
        kpi(s.errorGroups, 'Distinct error groups'),
        kpi(s.recurringErrorGroups, 'Recurring failures'),
        kpi(active, 'Active incidents'),
        kpi(s.meanTimeToResolveMinutes == null ? '–' : Math.round(s.meanTimeToResolveMinutes) + ' min', 'Mean time to resolve'),
    ].join('');

    $('#impact').innerHTML = s.serviceImpact.length ? `<table>
        <tr><th>Service</th><th>First error</th><th>Last error</th><th class="num">Errors</th><th class="num">Groups</th></tr>
        ${s.serviceImpact.map((i) => `<tr><td>${esc(i.service)}</td><td>${fmt(i.firstError)}</td><td>${fmt(i.lastError)}</td>
            <td class="num">${i.errorCount}</td><td class="num">${i.distinctErrorGroups}</td></tr>`).join('')}
        </table>` : '<p class="muted">No errors yet.</p>';

    $('#active').innerHTML = s.activeIncidents.length ? incidentTable(s.activeIncidents) : '<p class="muted">No active incidents.</p>';
    $('#top').innerHTML = groupTable(s.topErrorGroups);
    bindRows();
}

/* ---------- error groups ---------- */
async function loadGroups() {
    const list = await api('/api/error-groups?' + qs({ service: $('#g-service').value, recurringOnly: $('#g-recurring').checked }));
    $('#group-list').innerHTML = `<div class="card">${list.length ? groupTable(list) : '<p class="muted">No error groups.</p>'}</div>`;
    bindRows();
}
$('#g-service').addEventListener('change', loadGroups);
$('#g-recurring').addEventListener('change', loadGroups);

function groupTable(groups) {
    return `<table>
      <tr><th>Exception / signature</th><th>Services</th><th>First seen</th><th>Last seen</th><th class="num">Count</th><th>Incident</th></tr>
      ${groups.map((g) => `<tr class="click" data-group="${g.id}">
        <td><strong>${short(g.exceptionType)}</strong> ${g.recurring ? '<span class="pill sev-HIGH">RECURRING</span>' : ''}
            <div class="mono muted">${esc(g.normalizedMessage)}</div></td>
        <td>${tags(g.services)}</td><td>${fmt(g.firstSeen)}</td><td>${fmt(g.lastSeen)}</td>
        <td class="num">${g.occurrences}</td>
        <td>${g.incidentId ? `<a href="#" data-incident="${g.incidentId}">#${g.incidentId}</a> <span class="muted">${g.incidentStatus}</span>` : '<span class="muted">–</span>'}</td>
      </tr>`).join('')}
    </table>`;
}

async function openGroup(id) {
    const d = await api('/api/error-groups/' + id);
    const g = d.group;
    const max = Math.max(1, ...d.timeline.map((p) => p.count));
    openDrawer(`
      <h2>${short(g.exceptionType)} <span class="muted">· group #${g.id}</span></h2>
      <p class="mono">${esc(g.normalizedMessage)}</p>
      <p>Seen <strong>${g.occurrences}</strong> times in ${g.activeHours} distinct hour(s) across ${tags(g.services)}<br>
         First: ${fmt(g.firstSeen)} · Last: ${fmt(g.lastSeen)} · Highest level: <span class="lvl-${g.highestLevel}">${g.highestLevel}</span></p>
      ${g.incidentId ? `<p>Linked incident: <a href="#" data-incident="${g.incidentId}">#${g.incidentId}</a> (${g.incidentStatus})</p>`
                     : `<button class="btn" id="g-open-incident">Open incident for this group</button>`}
      <h3>Occurrences per hour</h3>
      <div class="bars">${d.timeline.map((p) => `<div title="${esc(p.hour)}: ${p.count}" style="height:${(p.count / max) * 100}%"></div>`).join('')}</div>
      <div class="muted">${fmt(d.timeline[0]?.hour)} → ${fmt(d.timeline.at(-1)?.hour)}</div>
      <h3>Recent occurrences</h3>
      ${logTable(d.recentEntries)}`);
    $('#g-open-incident')?.addEventListener('click', async () => {
        const created = await api('/api/incidents', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title: `${short(g.exceptionType)}: ${g.sampleMessage}`.slice(0, 200), severity: 'MEDIUM', errorGroupId: g.id }),
        });
        openIncident(created.summary.id);
    });
}

/* ---------- incidents ---------- */
STATUSES.forEach((s) => $('#i-status').insertAdjacentHTML('beforeend', `<option>${s}</option>`));
SEVERITIES.forEach((s) => $('#i-severity').insertAdjacentHTML('beforeend', `<option>${s}</option>`));
$('#i-search').addEventListener('click', loadIncidents);
$('#i-q').addEventListener('keydown', (e) => e.key === 'Enter' && loadIncidents());
$('#i-new').addEventListener('click', newIncidentForm);

async function loadIncidents() {
    const page = await api('/api/incidents?' + qs({
        q: $('#i-q').value, status: $('#i-status').value, severity: $('#i-severity').value, service: $('#i-service').value, size: 100,
    }));
    $('#incident-list').innerHTML = `<div class="card"><p class="muted">${page.totalElements} incident(s)</p>
        ${page.content.length ? incidentTable(page.content) : ''}</div>`;
    bindRows();
}

function incidentTable(list) {
    return `<table>
      <tr><th>#</th><th>Title</th><th>Severity</th><th>Status</th><th>Services</th><th>Started</th><th class="num">Errors</th></tr>
      ${list.map((i) => `<tr class="click" data-incident="${i.id}">
        <td>${i.id}</td><td>${esc(i.title)}${i.recurrenceCount ? ` <span class="pill sev-HIGH">RECURRED ×${i.recurrenceCount}</span>` : ''}</td>
        <td>${sev(i.severity)}</td><td>${i.status}</td><td>${tags(i.affectedServices)}</td>
        <td>${fmt(i.startedAt)}</td><td class="num">${i.occurrences ?? '–'}</td></tr>`).join('')}
    </table>`;
}

async function openIncident(id) {
    const d = await api('/api/incidents/' + id);
    const i = d.summary;
    const opts = (all, cur) => all.map((v) => `<option ${v === cur ? 'selected' : ''}>${v}</option>`).join('');
    openDrawer(`
      <h2>#${i.id} ${esc(i.title)}</h2>
      <p>${sev(i.severity)} <strong>${i.status}</strong> · ${tags(i.affectedServices)}</p>
      <p class="muted">Failure began ${fmt(i.startedAt)} · detected ${fmt(i.detectedAt)} · resolved ${fmt(i.resolvedAt)}
         ${i.errorGroupId ? ` · <a href="#" data-group="${i.errorGroupId}">error group #${i.errorGroupId}</a> (${i.occurrences} errors)` : ''}</p>
      ${d.description ? `<pre class="stack">${esc(d.description)}</pre>` : ''}
      <h3>Update</h3>
      <div class="form-row">
        <select id="u-status">${opts(STATUSES, i.status)}</select>
        <select id="u-severity">${opts(SEVERITIES, i.severity)}</select>
        <input id="u-assignee" placeholder="Assignee" value="${esc(i.assignee)}">
      </div>
      <textarea id="u-notes" rows="3" placeholder="Resolution notes (required to resolve or close)">${esc(d.resolutionNotes)}</textarea>
      <button class="btn" id="u-save">Save</button> <span id="u-msg"></span>
      <h3>Add investigation note</h3>
      <div class="form-row"><input id="n-text" placeholder="What did you find?"><button class="btn secondary" id="n-add" style="flex:0">Add note</button></div>
      <h3>History</h3>
      <ul class="history">${d.history.map((e) => `<li><span class="muted">${fmt(e.timestamp)} · ${e.type}</span><br>${esc(e.message)}</li>`).join('')}</ul>`);

    $('#u-save').addEventListener('click', async () => {
        try {
            await api('/api/incidents/' + id, {
                method: 'PATCH', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ status: $('#u-status').value, severity: $('#u-severity').value, assignee: $('#u-assignee').value, resolutionNotes: $('#u-notes').value }),
            });
            openIncident(id);
            refreshCurrent();
        } catch (e) {
            $('#u-msg').innerHTML = `<span class="error">${esc(e.message)}</span>`;
        }
    });
    $('#n-add').addEventListener('click', async () => {
        if (!$('#n-text').value.trim()) return;
        await api(`/api/incidents/${id}/notes`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ note: $('#n-text').value }),
        });
        openIncident(id);
    });
}

function newIncidentForm() {
    openDrawer(`
      <h2>New incident</h2>
      <div class="form-row"><input id="c-title" placeholder="Title"></div>
      <div class="form-row"><select id="c-severity">${SEVERITIES.map((s) => `<option>${s}</option>`).join('')}</select>
        <input id="c-services" placeholder="Affected services (comma separated)"><input id="c-assignee" placeholder="Assignee"></div>
      <textarea id="c-desc" rows="4" placeholder="Description"></textarea>
      <button class="btn" id="c-save">Create</button> <span id="c-msg"></span>`);
    $('#c-save').addEventListener('click', async () => {
        try {
            const created = await api('/api/incidents', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    title: $('#c-title').value, severity: $('#c-severity').value, description: $('#c-desc').value, assignee: $('#c-assignee').value,
                    affectedServices: $('#c-services').value.split(',').map((s) => s.trim()).filter(Boolean),
                }),
            });
            openIncident(created.summary.id);
            refreshCurrent();
        } catch (e) {
            $('#c-msg').innerHTML = `<span class="error">${esc(e.message)}</span>`;
        }
    });
}

/* ---------- logs ---------- */
$('#l-search').addEventListener('click', loadLogs);
$('#l-q').addEventListener('keydown', (e) => e.key === 'Enter' && loadLogs());

async function loadLogs() {
    const page = await api('/api/logs?' + qs({ q: $('#l-q').value, service: $('#l-service').value, minLevel: $('#l-level').value, size: 200 }));
    $('#log-list').innerHTML = `<div class="card"><p class="muted">${page.totalElements} matching entries${page.totalElements > page.content.length ? `, showing newest ${page.content.length}` : ''}</p>${logTable(page.content)}</div>`;
    bindRows();
}

function logTable(entries) {
    if (!entries.length) return '<p class="muted">No entries.</p>';
    return `<table>
      <tr><th>Time</th><th>Level</th><th>Service</th><th>Message</th></tr>
      ${entries.map((e) => `<tr>
        <td class="mono">${fmt(e.timestamp)}</td><td class="lvl-${e.level}">${e.level}</td><td>${esc(e.service)}</td>
        <td>${esc(e.message)}${e.errorGroupId ? ` <a href="#" data-group="${e.errorGroupId}" class="muted">group #${e.errorGroupId}</a>` : ''}
          ${e.stackTrace ? `<details><summary class="muted">stack trace</summary><pre class="stack">${esc(e.stackTrace)}</pre></details>` : ''}</td>
      </tr>`).join('')}
    </table>`;
}

/* ---------- ingest ---------- */
$('#upload').addEventListener('click', async () => {
    const file = $('#file').files[0];
    if (!file) return;
    const form = new FormData();
    form.append('file', file);
    showIngest(api('/api/logs/ingest', { method: 'POST', body: form }));
});
$('#paste-go').addEventListener('click', () => {
    if (!$('#paste').value.trim()) return;
    showIngest(api('/api/logs/ingest?source=pasted', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: $('#paste').value }));
});
async function showIngest(promise) {
    const out = $('#ingest-result');
    out.textContent = 'Analysing…';
    try {
        const r = await promise;
        out.textContent = `Source: ${r.source}
Lines read: ${r.linesRead} (skipped ${r.linesSkipped})
Entries stored: ${r.entriesStored}
Errors found: ${r.errorsFound}
New error groups: ${r.newErrorGroups}
Incidents opened: ${r.incidentsOpened.length ? r.incidentsOpened.map((i) => '#' + i).join(', ') : 'none'}
Incidents reopened (recurred): ${r.incidentsReopened.length ? r.incidentsReopened.map((i) => '#' + i).join(', ') : 'none'}`;
        loadServices();
    } catch (e) {
        out.innerHTML = `<span class="error">${esc(e.message)}</span>`;
    }
}

/* ---------- shared ---------- */
function openDrawer(html) {
    $('#drawer-body').innerHTML = `<button class="btn secondary close" onclick="drawer.close()">Close</button>${html}`;
    if (!$('#drawer').open) $('#drawer').showModal();
    bindRows($('#drawer-body'));
}

function bindRows(root = document) {
    root.querySelectorAll('[data-incident]').forEach((el) => (el.onclick = (e) => { e.preventDefault(); e.stopPropagation(); openIncident(el.dataset.incident); }));
    root.querySelectorAll('[data-group]').forEach((el) => (el.onclick = (e) => { e.preventDefault(); e.stopPropagation(); openGroup(el.dataset.group); }));
}

function refreshCurrent() {
    const active = document.querySelector('nav button.active')?.dataset.tab;
    loaders[active]?.();
}

async function loadServices() {
    const services = await api('/api/logs/services');
    ['#g-service', '#i-service', '#l-service'].forEach((sel) => {
        const el = $(sel);
        const current = el.value;
        el.length = 1;
        services.forEach((s) => el.insertAdjacentHTML('beforeend', `<option ${s === current ? 'selected' : ''}>${esc(s)}</option>`));
    });
}

loadServices();
loadOverview();
