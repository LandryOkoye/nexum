/* Nexum control-plane UI.
 *
 * Vanilla ES modules-free JavaScript, deliberately. A React build would add a
 * toolchain, a node_modules tree, a second deployable and a CORS story, and buy
 * nothing a judge can see. This ships as three static files served by the same
 * Spring Boot process that serves the API - same origin, no build step, and the
 * deployed artifact stays a single container.
 *
 * State lives in one object and the views re-render from it. At this size that
 * is easier to follow than any framework would be.
 */

'use strict';

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

const state = {
    goalId: null,
    goal: null,
    agents: [],
    tasks: [],
    runs: [],
    events: [],
    seenSeq: 0,
    stream: null,
    poller: null,
    ticker: null,
    recovery: null,
    /* Which task the trace panel is showing. Sticky once the operator picks
       one, so a poll cannot move it out from under them mid-read. */
    traceTaskId: null,
    tracePinned: false,
    trace: [],
    /* What each agent is doing right now, accumulated from the event stream.
       The REST views describe state; only the events describe activity, and an
       agent card that shows a status dot and nothing else hides the single most
       interesting thing in the system. */
    work: {},
    /* Signatures of the last painted panels. The poller runs every couple of
       seconds, and blindly re-rendering innerHTML on a timer replaces the very
       DOM nodes the operator is reaching for - a click that lands between
       mousedown and mouseup on a replaced node is simply lost. Repainting only
       on real change keeps the Kill button, the open dropdown, and any hover
       state stable. */
    painted: { agents: '', tasks: '', stats: '' },
};

/* The five events that constitute the recovery proof, in the order they must
   occur. The tracker below fills them in as they arrive. */
const RECOVERY_STEPS = [
    { type: 'AGENT_FAILED', label: 'Agent failed', hint: 'Lease lapsed — nobody declared it' },
    { type: 'TASK_ORPHANED', label: 'Task orphaned', hint: 'Returned to the pool, work intact' },
    { type: 'AGENT_REJOINED_GOAL', label: 'Agent joined', hint: 'A different agent enters the mission' },
    { type: 'CHECKPOINT_RESTORED', label: 'Checkpoint restored', hint: 'Resumes from the dead run' },
    { type: 'TASK_RESUMED', label: 'Task resumed', hint: 'Work continues, not restarts' },
];

const EVENT_KIND = {
    AGENT_FAILED: 'fail', TASK_FAILED: 'fail', RUN_HEARTBEAT_LOST: 'fail',
    TASK_ORPHANED: 'warn',
    TASK_COMPLETED: 'good', TASK_RESUMED: 'good', CHECKPOINT_RESTORED: 'good',
    AGENT_REJOINED_GOAL: 'good', AGENT_JOINED_GOAL: 'good',
    MEMORY_CREATED: 'mem', MEMORY_RETRIEVED: 'mem', MEMORY_PROMOTED: 'mem',
};

// --- http -----------------------------------------------------------------

async function api(path, options = {}) {
    const response = await fetch(path, {
        headers: { 'Content-Type': 'application/json' },
        ...options,
    });
    const text = await response.text();
    const body = text ? JSON.parse(text) : null;
    if (!response.ok) {
        throw new Error((body && body.error) || `${response.status} ${response.statusText}`);
    }
    return body;
}

// --- chrome ---------------------------------------------------------------

function toast(message, kind = 'info', detail = null) {
    const el = document.createElement('div');
    el.className = 'toast';
    el.dataset.kind = kind;
    el.textContent = message;
    if (detail) {
        const small = document.createElement('small');
        small.textContent = detail;
        el.append(small);
    }
    $('#toasts').append(el);
    setTimeout(() => el.remove(), kind === 'error' ? 7000 : 4500);
}

function connState(value, label) {
    const conn = $('#conn');
    conn.dataset.state = value;
    $('#conn-label').textContent = label;
}

const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const initials = (name) => String(name || '?').replace(/-.*$/, '').slice(0, 2).toUpperCase();

const shortId = (id) => String(id || '').slice(0, 8);

function clockOf(iso) {
    const date = new Date(iso);
    return Number.isNaN(date.getTime()) ? '--:--:--' : date.toTimeString().slice(0, 8);
}

// --- routing --------------------------------------------------------------

function render() {
    const hash = location.hash || '#/';
    teardown();
    const match = hash.match(/^#\/goal\/([0-9a-f-]{36})/i);
    if (match) {
        state.goalId = match[1];
        renderGoal();
    } else {
        state.goalId = null;
        renderGoals();
    }
}

/** Stops the stream and poller so leaving a goal cannot leave them running. */
function teardown() {
    if (state.stream) { state.stream.close(); state.stream = null; }
    if (state.poller) { clearInterval(state.poller); state.poller = null; }
    if (state.ticker) { clearInterval(state.ticker); state.ticker = null; }
    state.events = [];
    state.seenSeq = 0;
    state.recovery = null;
    state.traceTaskId = null;
    state.tracePinned = false;
    state.trace = [];
    state.work = {};
    // Cleared so the next goal paints from scratch rather than being skipped as
    // "unchanged" against the previous goal's signature.
    state.painted = { agents: '', tasks: '', stats: '', trace: '' };
    connState('idle', 'idle');
}

function mount(templateId) {
    const view = $('#view');
    view.replaceChildren($(`#${templateId}`).content.cloneNode(true));
    return view;
}

// --- missions view --------------------------------------------------------

async function renderGoals() {
    const view = mount('tpl-goals');

    view.addEventListener('click', async (event) => {
        const action = event.target.closest('[data-act]')?.dataset.act;
        if (action === 'new-goal') {
            $('#newgoal').hidden = false;
            $('#g-title').focus();
        } else if (action === 'cancel-goal') {
            $('#newgoal').hidden = true;
        } else if (action === 'seed') {
            const button = event.target.closest('button');
            await withBusy(button, async () => {
                const seeded = await api('/api/demo/seed', { method: 'POST' });
                toast('Demo mission seeded', 'ok',
                    '3 agents joined, 1 waiting outside, 3 tasks queued');
                location.hash = `#/goal/${seeded.goalId}`;
            });
        }
    });

    $('#newgoal').addEventListener('submit', async (event) => {
        event.preventDefault();
        const form = event.currentTarget;
        await withBusy($('button[type=submit]', form), async () => {
            const created = await api('/api/goals', {
                method: 'POST',
                body: JSON.stringify({
                    title: $('#g-title').value.trim(),
                    description: $('#g-desc').value.trim(),
                }),
            });
            location.hash = `#/goal/${created.goalId}`;
        });
    });

    try {
        const goals = await api('/api/goals');
        const list = $('#goal-list');
        if (!goals.length) {
            list.innerHTML = `<div class="empty"><strong>No missions yet</strong>
                Seed the demo mission to see the collective at work.</div>`;
            return;
        }
        list.innerHTML = goals.map((goal) => `
            <a class="goal-card" href="#/goal/${goal.id}">
              <h3>${escapeHtml(goal.title)}</h3>
              <p>${escapeHtml(goal.description || 'No description')}</p>
              <div class="mini-stats">
                <span><b>${goal.memberCount}</b> agents</span>
                <span><b>${goal.completedTaskCount}</b>/<b>${goal.taskCount}</b> tasks</span>
                <span><b>${goal.memoryCount}</b> memories</span>
              </div>
            </a>`).join('');
    } catch (error) {
        toast('Could not load missions', 'error', error.message);
    }
}

async function withBusy(button, work) {
    if (!button) { return work(); }
    const original = button.innerHTML;
    button.disabled = true;
    button.innerHTML = '<span class="spinner"></span> working';
    try {
        await work();
    } catch (error) {
        toast('Request failed', 'error', error.message);
    } finally {
        button.disabled = false;
        button.innerHTML = original;
    }
}

// --- goal view ------------------------------------------------------------

async function renderGoal() {
    const view = mount('tpl-goal');

    view.addEventListener('click', onGoalClick);
    $('#memory-form').addEventListener('submit', (event) => {
        event.preventDefault();
        recall();
    });

    await refresh();
    paintCluster();
    openStream();

    // The stream carries events; this refreshes the derived state those events
    // imply (task status, lease holders, run liveness). Cheap, and it keeps the
    // panels honest even if an event is missed entirely.
    state.poller = setInterval(refresh, 2000);
    // Separate and faster: the lease countdown is the one number that has to
    // move every second for a kill to be legible, and it updates text in place
    // rather than repainting anything.
    state.ticker = setInterval(tickLeases, 1000);
}

async function refresh() {
    if (!state.goalId) { return; }
    try {
        const [goal, agents, tasks, runs] = await Promise.all([
            api(`/api/goals/${state.goalId}`),
            api(`/api/goals/${state.goalId}/members`),
            api(`/api/goals/${state.goalId}/tasks`),
            api(`/api/goals/${state.goalId}/runs`),
        ]);
        state.goal = goal;
        state.agents = agents;
        state.tasks = tasks;
        state.runs = runs;
        paintGoal();
        paintAgents();
        paintTasks();
        refreshTrace();
    } catch (error) {
        connState('down', 'error');
    }
}

/** Returns true when the value differs from what was last painted. */
function changed(key, value) {
    const signature = JSON.stringify(value);
    if (state.painted[key] === signature) { return false; }
    state.painted[key] = signature;
    return true;
}

function paintGoal() {
    const goal = state.goal;
    $('#goal-title').textContent = goal.title;
    $('#goal-desc').textContent = goal.description || '';

    if (!changed('stats', [goal.memberCount, goal.completedTaskCount, goal.taskCount,
        goal.memoryCount, state.events.length])) {
        return;
    }
    $('#goal-stats').innerHTML = `
        <div class="stat"><span>Agents</span><b>${goal.memberCount}</b></div>
        <div class="stat"><span>Tasks</span><b>${goal.completedTaskCount}/${goal.taskCount}</b></div>
        <div class="stat"><span>Memories</span><b>${goal.memoryCount}</b></div>
        <div class="stat"><span>Events</span><b>${state.events.length}</b></div>`;
}

/** The live run for an agent, if this process is currently hosting one. */
function liveRunOf(agentId) {
    return state.runs.find((run) => run.agentId === agentId && run.status === 'RUNNING');
}

/**
 * The block on an agent card showing what it is doing right now.
 *
 * Assembled from three sources because no single one has it: the run says which
 * task, the task says how long the lease has left, and only the event stream
 * says which step the agent is on and what it just did.
 */
function workOf(agent, run) {
    if (!run) { return ''; }

    const task = state.tasks.find((t) => t.id === run.taskId);
    const work = state.work[agent.id] || {};
    const max = task ? task.maxSteps : 0;
    const step = work.step || 0;

    const ticks = max
        ? `<div class="steps" aria-label="step ${step} of ${max}">${
            Array.from({ length: max }, (unused, i) =>
                `<span class="step-tick" data-done="${i < step}"></span>`).join('')}</div>`
        : '';

    // data-expires is read by tickLeases once a second. Rendering the countdown
    // as text here too means it is correct on first paint rather than blank
    // until the next tick.
    const lease = task && task.leaseExpiresAt
        ? `<span class="lease" data-expires="${task.leaseExpiresAt}">${leaseText(task.leaseExpiresAt)}</span>`
        : '<span class="lease" data-state="lapsed">no lease</span>';

    return `
        <div class="agent-work">
          <div class="agent-work-top">
            <span>${step && max ? `step ${step}/${max}` : 'starting'}</span>
            ${lease}
          </div>
          ${ticks}
          <div class="agent-work-task">${escapeHtml(task ? task.title : 'claiming a task…')}</div>
          ${work.action ? `<div class="agent-work-action">${escapeHtml(work.action)}${
              work.detail ? ` — ${escapeHtml(work.detail)}` : ''}</div>` : ''}
        </div>`;
}

/** Seconds until a lease expires, as the operator would say it. */
function leaseText(expiresAt) {
    const left = Math.round((new Date(expiresAt).getTime() - Date.now()) / 1000);
    if (Number.isNaN(left)) { return 'no lease'; }
    return left > 0 ? `lease ${left}s` : `lapsed ${Math.abs(left)}s ago`;
}

/**
 * Updates lease countdowns in place, once a second.
 *
 * Text nodes only - never innerHTML. This is what makes a killed agent legible:
 * the heartbeat stops, the number runs down in front of you, and then the reaper
 * acts. Repainting the card to achieve that would destroy the Kill button
 * between mousedown and mouseup.
 */
function tickLeases() {
    $$('.lease[data-expires]').forEach((el) => {
        const left = Math.round((new Date(el.dataset.expires).getTime() - Date.now()) / 1000);
        el.textContent = leaseText(el.dataset.expires);
        el.dataset.state = left > 3 ? 'ok' : left > 0 ? 'expiring' : 'lapsed';
    });
}

function paintAgents() {
    const host = $('#agent-list');

    // Keyed on everything the card actually shows, so a heartbeat timestamp
    // ticking over does not count as a change. The lease countdown is excluded
    // deliberately - it is updated in place by tickLeases rather than by
    // repainting, so that a number changing once a second cannot swallow a
    // click on Kill.
    if (!changed('agents', state.agents.map((agent) => {
        const run = liveRunOf(agent.id);
        const work = state.work[agent.id];
        return [
            agent.id, agent.name, agent.role, agent.membershipStatus, agent.memoriesAuthored,
            run?.id ?? null,
            state.runs.some((r) => r.agentId === agent.id && r.status === 'DEAD'),
            work?.action ?? null, work?.step ?? null, work?.detail ?? null,
        ];
    }))) {
        return;
    }

    if (!state.agents.length) {
        host.innerHTML = `<div class="empty"><strong>No agents yet</strong>
            Add one to start work on this mission.</div>`;
        return;
    }

    host.innerHTML = state.agents.map((agent) => {
        const run = liveRunOf(agent.id);
        const left = agent.membershipStatus !== 'ACTIVE';
        const dead = state.runs.some((r) => r.agentId === agent.id && r.status === 'DEAD')
            && !run;

        const pill = run
            ? '<span class="pill pill-run">running</span>'
            : left
                ? '<span class="pill pill-out">left</span>'
                : dead
                    ? '<span class="pill pill-dead">run died</span>'
                    : '<span class="pill pill-idle">idle</span>';

        const actions = run
            ? `<button class="btn btn-sm btn-danger" data-act="kill" data-run="${run.id}"
                       aria-label="Kill the run of ${escapeHtml(agent.name)}">
                 <svg class="icon" aria-hidden="true"><use href="#i-kill"/></svg> Kill
               </button>`
            : `<button class="btn btn-sm" data-act="start" data-agent="${agent.id}">
                 <svg class="icon" aria-hidden="true"><use href="#i-play"/></svg> Start
               </button>`;

        return `
            <div class="agent" data-live="${Boolean(run)}" data-dead="${dead}">
              <div class="agent-main">
                <div class="avatar" aria-hidden="true">${escapeHtml(initials(agent.name))}</div>
                <div style="min-width:0;flex:1">
                  <div class="agent-name">${escapeHtml(agent.name.replace(/-.*$/, ''))}</div>
                  <div class="agent-role">${escapeHtml(agent.role.toLowerCase())}</div>
                </div>
                ${pill}
              </div>
              ${workOf(agent, run)}
              <div class="agent-meta">
                <span>${agent.memoriesAuthored} memories</span>
                ${run ? `<span>run ${shortId(run.id)}</span>` : ''}
              </div>
              <div class="agent-actions">${actions}</div>
            </div>`;
    }).join('');

    const select = $('#as-agent');
    const previous = select.value;
    select.innerHTML = state.agents
        .map((agent) => `<option value="${agent.id}">${escapeHtml(agent.name.replace(/-.*$/, ''))}
            — ${escapeHtml(agent.role.toLowerCase())}${agent.membershipStatus !== 'ACTIVE' ? ' (left)' : ''}</option>`)
        .join('');
    if (previous && state.agents.some((a) => a.id === previous)) { select.value = previous; }
}

function paintTasks() {
    const host = $('#task-list');

    if (!changed('tasks', state.tasks.map((task) => [
        task.id, task.title, task.status, task.attemptCount, task.checkpointCount,
        task.leaseRunId, task.id === state.traceTaskId,
    ]))) {
        return;
    }

    if (!state.tasks.length) {
        host.innerHTML = `<div class="empty"><strong>No tasks</strong>
            Add one for the agents to claim.</div>`;
        return;
    }

    const pillFor = {
        RUNNING: 'pill-run', ORPHANED: 'pill-orphan',
        COMPLETED: 'pill-done', FAILED: 'pill-dead',
    };

    host.innerHTML = state.tasks.map((task) => `
        <div class="task" data-status="${task.status}" data-act="trace" data-task="${task.id}"
             role="button" tabindex="0" aria-pressed="${task.id === state.traceTaskId}"
             style="cursor:pointer${task.id === state.traceTaskId
                 ? ';background:var(--surface-2)' : ''}">
          <div class="task-top">
            <span class="task-title">${escapeHtml(task.title)}</span>
            <span class="pill ${pillFor[task.status] || 'pill-idle'}">${task.status.toLowerCase()}</span>
          </div>
          <div class="task-meta">
            <span>attempt ${task.attemptCount}</span>
            <span>${task.checkpointCount} checkpoints</span>
            ${task.leaseRunId ? `<span>leased to ${shortId(task.leaseRunId)}</span>`
                              : '<span>no lease</span>'}
          </div>
        </div>`).join('');
}

// --- task trace -----------------------------------------------------------

/**
 * Picks which task the trace shows when the operator has not chosen one.
 *
 * Prefers the task with the most steps, because that is where the interesting
 * history is - and on a recovered mission it is the task that changed hands.
 * Stops choosing as soon as a human clicks something.
 */
function defaultTraceTask() {
    if (state.tracePinned && state.tasks.some((t) => t.id === state.traceTaskId)) {
        return state.traceTaskId;
    }
    const best = [...state.tasks].sort((a, b) => b.checkpointCount - a.checkpointCount)[0];
    return best ? best.id : null;
}

async function refreshTrace() {
    const taskId = defaultTraceTask();
    if (!taskId) {
        state.trace = [];
        paintTrace();
        return;
    }
    state.traceTaskId = taskId;
    try {
        state.trace = await api(`/api/goals/${state.goalId}/tasks/${taskId}/trace`);
    } catch {
        state.trace = [];
    }
    paintTrace();
}

function paintTrace() {
    const host = $('#trace');
    const label = $('#trace-task');
    if (!host) { return; }

    const task = state.tasks.find((t) => t.id === state.traceTaskId);
    if (label) { label.textContent = task ? task.title.slice(0, 42) : ''; }

    if (!changed('trace', [state.traceTaskId, state.trace.map((s) =>
        [s.seq, s.agentId, s.action])])) {
        return;
    }

    if (!state.trace.length) {
        host.innerHTML = `<div class="empty"><strong>No steps yet</strong>
            Start an agent — every step it takes is checkpointed here, against the task
            rather than against the agent.</div>`;
        return;
    }

    host.innerHTML = state.trace.map((step, index) => {
        const previous = state.trace[index - 1];
        // The seam. Drawn between two adjacent steps whose author differs, which
        // on a recovered task is the whole argument made visible: one sequence,
        // uninterrupted numbering, different hands.
        const handover = previous && previous.agentId !== step.agentId
            ? `<div class="trace-handover">
                 <span>↳</span>
                 <div>Work changed hands — <strong>${escapeHtml(short(previous.agentName))}</strong>
                 stopped, <strong>${escapeHtml(short(step.agentName))}</strong> continued from
                 step ${previous.seq}. The sequence does not restart.</div>
               </div>`
            : '';

        return `${handover}
            <div class="trace-step">
              <div class="trace-seq">${step.seq}</div>
              <div class="trace-body">
                <div class="trace-head">
                  <span class="trace-agent">${escapeHtml(short(step.agentName))}</span>
                  <span class="trace-action">${escapeHtml(step.action || '')}</span>
                  <span class="trace-action">${clockOf(step.at)}</span>
                </div>
                ${step.reason ? `<div class="trace-reason">${escapeHtml(step.reason)}</div>` : ''}
              </div>
            </div>`;
    }).join('');
}

/** Agent display names carry a seed suffix that is noise on screen. */
const short = (name) => String(name || 'unknown').replace(/-.*$/, '');

// --- actions --------------------------------------------------------------

async function onGoalClick(event) {
    const trigger = event.target.closest('[data-act]');
    if (!trigger) { return; }
    const action = trigger.dataset.act;

    if (action === 'start') {
        await withBusy(trigger, async () => {
            await api(`/api/goals/${state.goalId}/agents/${trigger.dataset.agent}/runs`,
                { method: 'POST' });
            await refresh();
        });
    } else if (action === 'kill') {
        await withBusy(trigger, async () => {
            await api(`/api/runs/${trigger.dataset.run}/kill`, { method: 'POST' });
            toast('Worker killed — the task was not touched', 'kill',
                'Its lease will lapse and the reaper has to notice on its own.');
            await refresh();
        });
    } else if (action === 'trace') {
        // Pins the panel to the operator's choice, so the poller stops picking.
        state.tracePinned = true;
        state.traceTaskId = trigger.dataset.task;
        paintTasks();
        await refreshTrace();
    } else if (action === 'toggle-plan') {
        const plan = $('#crdb-plan');
        plan.hidden = !plan.hidden;
        trigger.setAttribute('aria-expanded', String(!plan.hidden));
    } else if (action === 'add-agent') {
        const name = prompt('Agent name', 'Ekon');
        if (!name) { return; }
        const role = prompt('Role: RESEARCHER, ANALYST, PLANNER, STRATEGIST, REVIEWER, '
            + 'EXECUTOR or SUPERVISOR', 'ANALYST');
        if (!role) { return; }
        try {
            const agent = await api('/api/agents', {
                method: 'POST',
                body: JSON.stringify({ name: name.trim(), role: role.trim().toUpperCase() }),
            });
            await api(`/api/goals/${state.goalId}/members`, {
                method: 'POST',
                body: JSON.stringify({ agentId: agent.agentId, role: role.trim().toUpperCase() }),
            });
            toast(`${name} joined the mission`, 'ok');
            await refresh();
        } catch (error) {
            toast('Could not add agent', 'error', error.message);
        }
    } else if (action === 'add-task') {
        const title = prompt('Task title');
        if (!title) { return; }
        try {
            await api(`/api/goals/${state.goalId}/tasks`, {
                method: 'POST',
                body: JSON.stringify({ title: title.trim(), description: '', priority: 10 }),
            });
            await refresh();
        } catch (error) {
            toast('Could not add task', 'error', error.message);
        }
    }
}

// --- memory ---------------------------------------------------------------

async function recall() {
    const agentId = $('#as-agent').value;
    if (!agentId) { return; }
    const query = $('#mem-q').value.trim();
    const meta = $('#memory-meta');
    const list = $('#memory-list');

    meta.innerHTML = '<span class="spinner"></span> recalling…';
    try {
        const result = await api(`/api/goals/${state.goalId}/memory`
            + `?asAgent=${agentId}&query=${encodeURIComponent(query)}&limit=25`);

        const agent = state.agents.find((a) => a.id === agentId);
        const name = agent ? agent.name.replace(/-.*$/, '') : 'agent';
        const strategyPill = result.strategy === 'SEMANTIC'
            ? '<span class="pill pill-done">vector ranked</span>'
            : '<span class="pill pill-idle">confidence + recency</span>';

        meta.innerHTML = `<span><b>${result.count}</b> visible to ${escapeHtml(name)}</span>
            ${strategyPill}`;

        if (!result.count) {
            list.innerHTML = `<div class="empty"><strong>Nothing visible</strong>
                ${escapeHtml(name)} cannot see any memory on this goal — either nothing has
                been written yet, or this agent is not an active member.</div>`;
            // Still worth explaining: an empty result because the policy issued no
            // grants is a different thing from an empty result because the goal has
            // no memories, and the plan is what tells them apart.
            showPlan(agentId, query);
            return;
        }

        list.innerHTML = result.memories.map((memory) => {
            const author = state.agents.find((a) => a.id === memory.authorAgentId);
            const low = memory.confidence <= 0.5;
            return `
                <article class="memory" data-scope="${memory.scope}">
                  <div class="memory-top">
                    <span class="pill ${memory.scope === 'PRIVATE' ? 'pill-out' : 'pill-done'}">
                      ${memory.scope === 'PRIVATE' ? '<svg class="icon" aria-hidden="true" style="width:11px;height:11px"><use href="#i-lock"/></svg> ' : ''}${memory.scope.toLowerCase()}
                    </span>
                    <span class="pill pill-idle">${memory.type.toLowerCase()}</span>
                    ${memory.embeddingStatus !== 'READY'
                        ? `<span class="pill pill-orphan">${memory.embeddingStatus.toLowerCase()} vector</span>` : ''}
                  </div>
                  <div class="memory-content">${escapeHtml(memory.content)}</div>
                  <div class="memory-foot">
                    <span class="conf" title="Confidence, capped by the backend when no evidence backs the claim">
                      conf
                      <span class="conf-bar"><span class="conf-fill" data-low="${low}"
                        style="width:${Math.round(memory.confidence * 100)}%"></span></span>
                      ${memory.confidence.toFixed(2)}
                    </span>
                    <span>by ${escapeHtml(author ? author.name.replace(/-.*$/, '') : 'unknown agent')}</span>
                    ${memory.source ? `<span>${escapeHtml(memory.source)}</span>` : ''}
                    ${memory.distance != null
                        ? `<span>distance ${memory.distance.toFixed(4)}</span>` : ''}
                  </div>
                </article>`;
        }).join('');

        showPlan(agentId, query);
    } catch (error) {
        meta.textContent = '';
        list.innerHTML = `<div class="empty"><strong>Recall failed</strong>
            ${escapeHtml(error.message)}</div>`;
    }
}

// --- cockroachdb inspection -----------------------------------------------

// Names what we are connected to. The wire protocol is PostgreSQL's, so without
// this nothing on screen distinguishes CockroachDB from Postgres - and the
// vector indexes listed here could not exist on Postgres at all.
async function paintCluster() {
    const box = $('#crdb-cluster');
    if (!box) { return; }
    try {
        const cluster = await api('/api/cockroach');
        // "CockroachDB CCL v25.4.14 (x86_64-...)" - the release is the part worth
        // showing; the build triple is noise on a dashboard.
        const release = (cluster.version.match(/CockroachDB \S+ (v\S+)/) || [])[1]
            || cluster.version;
        const index = cluster.vectorIndexes.find((i) => i.name.includes('scope'))
            || cluster.vectorIndexes[0];
        box.innerHTML = `
            <span class="crdb-badge" title="${escapeHtml(cluster.version)}">CockroachDB ${escapeHtml(release)}</span>
            <span class="crdb-fact">${escapeHtml(cluster.isolation)}</span>
            ${index ? `<code class="crdb-idx">${escapeHtml(index.definition.replace(/^VECTOR INDEX \S+ /, ''))}</code>` : ''}`;
    } catch {
        box.innerHTML = '';
    }
}

// Shows how the query that just ran was executed. Rendered after the results
// rather than instead of them: the results are the answer, this is the evidence
// for how it was reached. A plan that says `scan` is shown exactly as
// prominently as one that says `vector search` - a panel that only appears when
// the news is good would be decoration.
async function showPlan(agentId, query) {
    const box = $('#crdb');
    const grants = $('#crdb-grants');
    const full = $('#crdb-plan');
    if (!box) { return; }

    try {
        const plan = await api(`/api/cockroach/goals/${state.goalId}/recall-plan`
            + `?asAgent=${agentId}&query=${encodeURIComponent(query)}&limit=25`);

        box.hidden = false;

        if (!plan.plans.length) {
            grants.innerHTML = `<div class="crdb-grant crdb-grant-none">
                <strong>No query ran.</strong> The access policy returned no grants for this
                agent, so retrieval stopped before it reached the memories table. Denial
                happens above the database, not by filtering rows out of a result.</div>`;
            full.textContent = '';
            return;
        }

        grants.innerHTML = plan.plans.map((p) => `
            <div class="crdb-grant" data-vector="${p.vectorSearch}">
              <div class="crdb-grant-top">
                <span class="pill ${p.vectorSearch ? 'pill-done' : 'pill-orphan'}">${p.vectorSearch ? 'vector search' : 'scan'}</span>
                ${p.index ? `<code>${escapeHtml(p.index.replace(/^memories@/, ''))}</code>` : ''}
                ${p.executionTime ? `<span class="crdb-time">${escapeHtml(p.executionTime)}</span>` : ''}
              </div>
              <code class="crdb-pred">${escapeHtml(p.predicate)}</code>
              ${p.prefixSpans ? `<div class="crdb-spans"><span>index prefix</span><code>${escapeHtml(p.prefixSpans)}</code></div>` : ''}
            </div>`).join('')
            + `<p class="crdb-note">Scope is constrained in the index prefix — the engine
                never ranks a memory this agent may not see. Vector built from
                ${escapeHtml(plan.vectorSource)}, ${plan.dimensions} dimensions.</p>`;

        full.textContent = plan.plans
            .map((p) => `-- ${p.predicate}\n${p.lines.join('\n')}`)
            .join('\n\n');
    } catch (error) {
        box.hidden = false;
        grants.innerHTML = `<div class="crdb-grant crdb-grant-none">
            Could not read the plan — ${escapeHtml(error.message)}</div>`;
        full.textContent = '';
    }
}

// --- event stream ---------------------------------------------------------

function openStream() {
    connState('idle', 'connecting');
    const stream = new EventSource(`/api/goals/${state.goalId}/events/stream?after=0`);
    state.stream = stream;

    stream.onopen = () => connState('live', 'live');
    stream.onerror = () => {
        // EventSource reconnects on its own, resending Last-Event-ID, so the
        // server resumes exactly where this client left off. Nothing to do but
        // say so.
        connState('down', 'reconnecting');
    };

    // Every event type is delivered under its own SSE `event:` name, so a
    // single catch-all listener is not enough - listen per type.
    const types = new Set([...Object.keys(EVENT_KIND), 'GOAL_CREATED', 'TASK_CREATED',
        'TASK_CLAIMED', 'RUN_STARTED', 'CHECKPOINT_SAVED', 'DECISION_CREATED',
        'TOOL_CALLED', 'RECOVERY_STARTED']);
    types.forEach((type) => stream.addEventListener(type, onEvent));
}

function onEvent(message) {
    let event;
    try {
        event = JSON.parse(message.data);
    } catch {
        return;
    }

    state.events.push(event);
    state.seenSeq = Math.max(state.seenSeq, event.seq);
    noteWork(event);
    appendEvent(event);
    trackRecovery(event);
}

/**
 * Keeps the per-agent "doing right now" line current.
 *
 * The REST views cannot supply this: they report committed state, and what an
 * agent is doing between checkpoints exists only as an event. Resolved through
 * the run rather than the payload's agent id because tool calls carry a run id
 * and nothing else.
 */
function noteWork(event) {
    const payload = event.payload || {};
    const agentId = payload.agentId
        || state.runs.find((run) => run.id === payload.runId)?.agentId;
    if (!agentId) { return; }

    const current = state.work[agentId] || {};
    switch (event.type) {
        case 'TOOL_CALLED':
            state.work[agentId] = { ...current, action: 'searching',
                detail: payload.query || '' };
            break;
        case 'MEMORY_CREATED':
            state.work[agentId] = { ...current, action: 'recording a finding',
                detail: (payload.content || '').slice(0, 60) };
            break;
        case 'CHECKPOINT_SAVED':
            state.work[agentId] = { ...current, step: payload.step || current.step,
                action: current.action, detail: current.detail };
            break;
        case 'TASK_COMPLETED':
            state.work[agentId] = { ...current, action: 'completed', detail: '' };
            break;
        case 'RUN_STARTED':
        case 'TASK_CLAIMED':
            state.work[agentId] = { step: 0, action: 'claiming', detail: '' };
            break;
        default:
            return;
    }
    paintAgents();
}

/* Which rail colour an event gets. Grouped by what the event means to a viewer
   rather than by which table it came from. */
const ACT_KIND = {
    AGENT_FAILED: 'kill', RUN_HEARTBEAT_LOST: 'kill', TASK_FAILED: 'kill',
    TASK_ORPHANED: 'orphan',
    TASK_COMPLETED: 'done', TASK_RESUMED: 'done', CHECKPOINT_RESTORED: 'done',
    MEMORY_CREATED: 'memory', MEMORY_RETRIEVED: 'memory', MEMORY_PROMOTED: 'memory',
    RUN_STARTED: 'run', TASK_CLAIMED: 'run', TOOL_CALLED: 'run',
};

/* Verbs, not table names. TOOL_CALLED means nothing to a viewer; "searched"
   does. */
const ACT_VERB = {
    TOOL_CALLED: 'searched', MEMORY_CREATED: 'recorded', MEMORY_RETRIEVED: 'recalled',
    CHECKPOINT_SAVED: 'checkpoint', CHECKPOINT_RESTORED: 'restored',
    TASK_CLAIMED: 'claimed', TASK_COMPLETED: 'completed', TASK_RESUMED: 'resumed',
    TASK_ORPHANED: 'orphaned', AGENT_FAILED: 'failed', RUN_STARTED: 'started',
    AGENT_JOINED_GOAL: 'joined', AGENT_REJOINED_GOAL: 'joined',
};

function appendEvent(event) {
    const list = $('#activity');
    if (!list) { return; }

    const payload = event.payload || {};
    const li = document.createElement('li');
    li.className = 'act';
    li.dataset.kind = ACT_KIND[event.type] || 'info';
    li.innerHTML = `
        <div class="act-time">${clockOf(event.at)}</div>
        <div class="act-body">
          <div class="act-head">
            <span class="act-who">${escapeHtml(whoOf(event))}</span>
            <span class="act-what">${escapeHtml(ACT_VERB[event.type] || event.type.toLowerCase())}</span>
          </div>
          ${bodyOf(event, payload)}
        </div>`;
    list.append(li);

    if ($('#follow')?.checked) {
        list.scrollTop = list.scrollHeight;
    }
    while (list.children.length > 400) { list.firstElementChild.remove(); }
}

/** Names the actor, falling back to the system when no agent is responsible. */
function whoOf(event) {
    const payload = event.payload || {};
    const agentId = payload.agentId
        || state.runs.find((r) => r.id === payload.runId)?.agentId;
    const agent = state.agents.find((a) => a.id === agentId);
    if (agent) { return short(agent.name); }
    // The reaper has no agent. Saying so is the point: nothing declared the
    // failure, a sweep noticed it.
    return (event.type === 'TASK_ORPHANED' || event.type === 'AGENT_FAILED')
        ? 'reaper' : 'system';
}

/**
 * The detail lines under an activity row.
 *
 * Search results are rendered as real links because a finding a reader cannot
 * open is a finding they have to take on faith - which is exactly the failure
 * mode the evidence model exists to prevent.
 */
function bodyOf(event, payload) {
    switch (event.type) {
        case 'TOOL_CALLED': {
            const sources = Array.isArray(payload.sources) ? payload.sources : [];
            return `<div class="act-query">${escapeHtml(payload.query || '')}</div>
                ${sources.length ? `<div class="act-sources">${sources.map((source) => `
                    <div class="act-source">
                      <code>${escapeHtml(source.id || '')}</code>
                      ${source.url
                          ? `<a href="${escapeHtml(source.url)}" target="_blank" rel="noopener noreferrer"
                               >${escapeHtml(source.title || source.url)}</a>`
                          : `<span>${escapeHtml(source.title || '')}</span>`}
                    </div>`).join('')}</div>`
                    : `<div class="act-detail">no results</div>`}`;
        }
        case 'MEMORY_CREATED':
            return `<div class="act-detail">${escapeHtml(payload.content || '')}</div>
                <div class="act-source">
                  <code>${escapeHtml(payload.scope || '')} · conf ${payload.confidence}</code>
                  ${payload.evidenceCount ? `<span>${payload.evidenceCount} evidence</span>`
                      : '<span>unevidenced — confidence capped</span>'}
                </div>`;
        case 'MEMORY_RETRIEVED':
            return `<div class="act-detail">${payload.resultCount} results via
                ${escapeHtml(String(payload.strategy || '').toLowerCase())}</div>`;
        case 'CHECKPOINT_RESTORED':
            return `<div class="act-detail">from step ${payload.seq}, written by run
                ${shortId(payload.originallyWrittenByRun)} — a run that no longer exists</div>`;
        case 'AGENT_FAILED':
            return `<div class="act-detail">${escapeHtml(payload.reason || '')} — the lease
                simply stopped being renewed</div>`;
        case 'TASK_ORPHANED':
            return `<div class="act-detail">returned to the pool with its checkpoints
                intact</div>`;
        case 'TASK_RESUMED':
            return `<div class="act-detail">attempt ${payload.attempt}, continuing from
                step ${payload.fromSeq}</div>`;
        case 'TASK_COMPLETED':
            return `<div class="act-detail">${escapeHtml(payload.summary || 'done')}</div>`;
        case 'TASK_CLAIMED':
            return `<div class="act-detail">${escapeHtml(payload.title || '')}</div>`;
        case 'CHECKPOINT_SAVED':
            return `<div class="act-detail">seq ${payload.seq} · ${escapeHtml(payload.progress || '')}</div>`;
        default:
            return `<div class="act-detail">${escapeHtml(detailOf(event))}</div>`;
    }
}

/** A one-line human summary; the payload shape differs per event type. */
function detailOf(event) {
    const p = event.payload || {};
    switch (event.type) {
        case 'MEMORY_CREATED':
            return `${p.scope} ${p.type} · conf ${p.confidence} · ${p.evidenceCount} evidence · ${p.content || ''}`;
        case 'MEMORY_RETRIEVED':
            return `${p.resultCount} results via ${p.strategy}`;
        case 'TOOL_CALLED':
            return `${p.tool} "${p.query}" → ${p.resultCount} documents`;
        case 'CHECKPOINT_SAVED':
            return `seq ${p.seq} · ${p.progress || ''}`;
        case 'CHECKPOINT_RESTORED':
            return `seq ${p.seq}, written by run ${shortId(p.originallyWrittenByRun)} — a run that is gone`;
        case 'AGENT_FAILED':
            return `run ${shortId(p.runId)} · ${p.reason}`;
        case 'TASK_ORPHANED':
            return `task ${shortId(p.taskId)} returned to the pool`;
        case 'TASK_RESUMED':
            return `attempt ${p.attempt}, resuming from checkpoint ${p.fromSeq}`;
        case 'TASK_CLAIMED':
            return `${p.title || ''} (attempt ${p.attempt})`;
        case 'TASK_COMPLETED':
            return p.summary || 'done';
        case 'AGENT_JOINED_GOAL':
        case 'AGENT_REJOINED_GOAL':
            return `agent ${shortId(p.agentId)} as ${p.role || 'member'}`;
        default:
            return Object.entries(p)
                .filter(([, v]) => v != null)
                .slice(0, 3)
                .map(([k, v]) => `${k}=${String(v).slice(0, 40)}`)
                .join(' · ');
    }
}

/**
 * Fills in the recovery tracker as the five events arrive.
 *
 * Starts on AGENT_FAILED and only accepts each subsequent step in order, so
 * unrelated joins earlier in the run cannot tick a box ahead of time - the
 * point is to show that this specific sequence happened, in this order.
 */
function trackRecovery(event) {
    const panel = $('#recovery');
    if (!panel) { return; }

    if (event.type === 'AGENT_FAILED') {
        state.recovery = { at: {}, next: 0 };
        panel.hidden = false;
        panel.dataset.done = 'false';
        $('#recovery-title').textContent = 'Failure detected — recovering';
    }
    if (!state.recovery) { return; }

    const expected = RECOVERY_STEPS[state.recovery.next];
    if (expected && event.type === expected.type) {
        state.recovery.at[expected.type] = event.at;
        state.recovery.next += 1;
    }

    const done = state.recovery.next >= RECOVERY_STEPS.length;
    panel.dataset.done = String(done);
    if (done) {
        $('#recovery-title').textContent = 'Recovered — the collective kept thinking';
    }

    $('#recovery-steps').innerHTML = RECOVERY_STEPS.map((step, index) => {
        const at = state.recovery.at[step.type];
        return `
            <li data-done="${Boolean(at)}">
              <span class="step-num" aria-hidden="true">${at ? '&check;' : index + 1}</span>
              <div class="step-body">
                <b>${step.label}</b>
                <span>${at ? clockOf(at) : step.hint}</span>
              </div>
            </li>`;
    }).join('');
}

// --- boot -----------------------------------------------------------------

window.addEventListener('hashchange', render);
window.addEventListener('beforeunload', teardown);
render();
