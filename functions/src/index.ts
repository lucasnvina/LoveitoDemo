import * as functions from 'firebase-functions';
import * as admin from 'firebase-admin';

admin.initializeApp();
const db = admin.firestore();

// ---- Firestore Collections (propuesta) ----
// flows/{flowId}/versions/{versionId} -> { definition: <json>, active: boolean, createdAt }
// flowSessions/{sessionId} -> {
//    userId, flowId, version, currentNodeId, vars: {}, startedAt, finishedAt?,
//    status: 'active'|'finished', triageLevel?, durationSec?, petId
// }
// flowSessions/{sessionId}/answers/{autoId} -> { nodeId, answer, ts }
// seizures/{episodeId} (opcional snapshot final) -> { userId, petId, triageLevel, durationSec, createdAt, vars }

// ---- Types ----
interface FlowDefinitionRoot {
  version: string;
  locale?: string;
  start_node: string;
  globals?: { vars?: Record<string, any> };
  event_hooks?: EventHook[];
  nodes: NodeDef[];
}
interface EventHook { id: string; event: string; interrupt?: boolean; conditions?: string[]; actions?: FlowAction[]; }
interface FlowAction { do: string; params?: Record<string, any>; }
interface BaseNode { id: string; type: string; }
interface InfoNode extends BaseNode { type: 'info'; prompt: string; actions?: FlowAction[]; next?: string|null; }
interface YesNoNode extends BaseNode { type: 'yes_no'; prompt: string; on_answer: AnswerRoute[]; }
interface EventNode extends BaseNode { type: 'event'; event: string; actions?: FlowAction[]; next?: string|null; }
interface ActionNode extends BaseNode { type: 'action'; prompt?: string; actions?: FlowAction[]; next?: string|null; }
interface BranchNode extends BaseNode { type: 'branch'; conditions: BranchCondition[]; }
interface AnswerRoute { when: string; actions?: FlowAction[]; next?: string|null; }
interface BranchCondition { when: string; actions?: FlowAction[]; next?: string|null; }

type NodeDef = InfoNode | YesNoNode | EventNode | ActionNode | BranchNode;

// ---- Simple in-memory cache for flow versions ----
const flowCache: Map<string, FlowDefinitionRoot> = new Map();

async function loadFlowDefinition(flowId: string, version?: string): Promise<FlowDefinitionRoot> {
  const cacheKey = version ? `${flowId}@${version}` : flowId;
  if (flowCache.has(cacheKey)) return flowCache.get(cacheKey)!;

  let docRef: FirebaseFirestore.DocumentReference;
  if (version) {
    docRef = db.collection('flows').doc(flowId).collection('versions').doc(version);
  } else {
    // Buscar versión activa
    const snap = await db.collection('flows').doc(flowId).collection('versions')
      .where('active', '==', true).limit(1).get();
    if (snap.empty) throw new functions.https.HttpsError('not-found', 'No flow version active');
    docRef = snap.docs[0].ref;
  }
  const doc = await docRef.get();
  if (!doc.exists) throw new functions.https.HttpsError('not-found', 'Flow version not found');
  const data = doc.data();
  const def = data?.definition as FlowDefinitionRoot;
  if (!def) throw new functions.https.HttpsError('failed-precondition', 'Invalid definition payload');
  flowCache.set(cacheKey, def);
  return def;
}

function buildNodeMap(def: FlowDefinitionRoot): Record<string, NodeDef> {
  const map: Record<string, NodeDef> = {};
  def.nodes.forEach(n => { map[n.id] = n; });
  return map;
}

// ---- Expression evaluator (minimal) ----
function evalExpr(exprRaw: string, ctx: { vars: Record<string, any>; app: Record<string, any>; elapsedSec: number }): boolean {
  const expr = exprRaw.trim().replace(/^\(/, '').replace(/\)$/,'');
  if (expr === 'else') return true;
  const parts = expr.split('&&').map(p=>p.trim());
  return parts.every(p => evalSimple(p, ctx));
}
function evalSimple(simple: string, ctx: { vars: Record<string, any>; app: Record<string, any>; elapsedSec: number }): boolean {
  const ops = ['>=','<=','==','>','<'];
  const op = ops.find(o => simple.includes(o));
  if (!op) return false;
  const [lRaw, rRaw] = simple.split(op).map(s=>s.trim());
  const lVal = resolveVal(lRaw, ctx); const rVal = resolveVal(rRaw, ctx);
  switch (op) {
    case '==': return lVal === rVal;
    case '>=': return Number(lVal) >= Number(rVal);
    case '<=': return Number(lVal) <= Number(rVal);
    case '>': return Number(lVal) > Number(rVal);
    case '<': return Number(lVal) < Number(rVal);
    default: return false;
  }
}
function resolveVal(token: string, ctx: { vars: Record<string, any>; app: Record<string, any>; elapsedSec: number }): any {
  if (token === 'true') return true; if (token === 'false') return false; if (token === 'null') return null;
  if (!isNaN(Number(token))) return Number(token);
  if (token.startsWith('$vars.')) return ctx.vars[token.substring(6)];
  if (token.startsWith('$app.')) return ctx.app[token.substring(5)];
  if (token === '$timer.elapsed_sec') return ctx.elapsedSec;
  return token.replace(/^"|"$/g, '');
}

// ---- Action execution (mutates vars) ----
function executeActions(actions: FlowAction[]|undefined, rt: RuntimeState) {
  (actions||[]).forEach(a => {
    switch (a.do) {
      case 'record_note': {
        const field = a.params?.field; let value = a.params?.value;
        if (value === '$timer.elapsed_sec') value = rt.elapsedSec; if (value === '$now.epoch') value = Math.floor(Date.now()/1000);
        if (field) rt.vars[field] = value; break;
      }
      case 'mark_emergency': rt.vars['triage_level'] = 'ROJO'; break;
      case 'mark_warning': rt.vars['triage_level'] = 'AMARILLO'; break;
      case 'redirect': {
        const id = a.params?.node_id; if (id) rt.currentNodeId = id; break;
      }
      case 'start_timer': if (!rt.startedAt) rt.startedAt = Date.now(); break;
      case 'stop_timer': rt.elapsedSec = Math.floor((Date.now() - (rt.startedAt||Date.now()))/1000); break;
      case 'say': /* no-op server side */ break;
    }
  });
}

interface RuntimeState {
  currentNodeId: string;
  vars: Record<string, any>;
  startedAt?: number;
  elapsedSec: number;
  finished: boolean;
}

function applyEventHooks(def: FlowDefinitionRoot, rt: RuntimeState) {
  const hooks = def.event_hooks || [];
  for (const h of hooks) {
    if (h.event !== 'timer_tick') continue;
    const ctx = { vars: rt.vars, app: {}, elapsedSec: rt.elapsedSec };
    const all = (h.conditions||[]).every(c => evalExpr(c, ctx));
    if (all) {
      executeActions(h.actions, rt);
      if (h.interrupt) break;
    }
  }
}

function advanceAuto(def: FlowDefinitionRoot, rt: RuntimeState, nodeMap: Record<string, NodeDef>) {
  while (!rt.finished) {
    const n = nodeMap[rt.currentNodeId];
    if (!n) { rt.finished = true; break; }
    if (n.type === 'info' || n.type === 'action') {
      executeActions((n as any).actions, rt);
      const next = (n as any).next; if (!next) { rt.finished = true; break; } rt.currentNodeId = next; continue;
    }
    if (n.type === 'branch') {
      const ctx = { vars: rt.vars, app: {}, elapsedSec: rt.elapsedSec };
      const branch = n as BranchNode;
      let matched = false;
      for (const c of branch.conditions) {
        if (c.when === 'else' || evalExpr(c.when, ctx)) {
          executeActions(c.actions, rt);
            if (!c.next) { rt.finished = true; } else { rt.currentNodeId = c.next; }
          matched = true; break;
        }
      }
      if (!matched) rt.finished = true;
      continue;
    }
    // yes_no y event requieren input, detener avance automático
    break;
  }
}

// ---- Callable: start session ----
export const startSeizureSession = functions.https.onCall(async (data, context) => {
  if (!context.auth) throw new functions.https.HttpsError('unauthenticated','Auth required');
  const userId = context.auth.uid;
  const flowId = (data.flowId as string) || 'seizure_assistant';
  const version = data.version as string | undefined;
  const petId = data.petId as string | undefined;
  const seizuresCount24h = data.seizuresCount24h || 0;

  const def = await loadFlowDefinition(flowId, version);
  const nodeMap = buildNodeMap(def);
  const vars: Record<string, any> = { ...(def.globals?.vars || {}) };
  vars['seizures_count_24h'] = seizuresCount24h;
  const rt: RuntimeState = {
    currentNodeId: def.start_node,
    vars,
    startedAt: Date.now(),
    elapsedSec: 0,
    finished: false
  };
  advanceAuto(def, rt, nodeMap);
  const sessionRef = await db.collection('flowSessions').add({
    userId, flowId, version: def.version, petId,
    currentNodeId: rt.currentNodeId, vars: rt.vars,
    startedAt: admin.firestore.FieldValue.serverTimestamp(), status: 'active'
  });

  return {
    sessionId: sessionRef.id,
    version: def.version,
    node: serializeNode(nodeMap[rt.currentNodeId]),
    finished: rt.finished,
    triageLevel: rt.vars['triage_level'] || null
  };
});

// ---- Callable: answer / event / tick ----
export const progressSeizureSession = functions.https.onCall(async (data, context) => {
  if (!context.auth) throw new functions.https.HttpsError('unauthenticated','Auth required');
  const userId = context.auth.uid;
  const sessionId = data.sessionId as string;
  const answer = data.answer as string | undefined; // yes/no (other inputs will map to 'other')
  const event = data.event as string | undefined; // user_confirms_seizure_stopped
  const timerSec = data.elapsedSec as number | undefined; // optional client reported

  const sessionRef = db.collection('flowSessions').doc(sessionId);
  const sessionSnap = await sessionRef.get();
  if (!sessionSnap.exists) throw new functions.https.HttpsError('not-found','Session not found');
  const session = sessionSnap.data()!;
  if (session.userId !== userId) throw new functions.https.HttpsError('permission-denied','Not owner');
  if (session.status === 'finished') return { finished: true, triageLevel: session.triageLevel };

  // Load flow definition
  const def = await loadFlowDefinition(session.flowId, session.version);
  const nodeMap = buildNodeMap(def);
  const vars: Record<string, any> = { ...(session.vars || {}) };
  const startedAtMs = (session.startedAt?.toMillis?.() || Date.now());
  let elapsedSec = Math.floor((Date.now() - startedAtMs)/1000);
  if (typeof timerSec === 'number' && timerSec > elapsedSec) elapsedSec = timerSec; // trust larger? conservative

  const rt: RuntimeState = {
    currentNodeId: session.currentNodeId,
    vars,
    startedAt: startedAtMs,
    elapsedSec,
    finished: false
  };

  applyEventHooks(def, rt); // timer hooks first (might redirect)

  if (!rt.finished) {
    const node = nodeMap[rt.currentNodeId];
    if (node && node.type === 'yes_no' && answer) {
      const yesNo = node as YesNoNode;
      const norm = normalizeAnswer(answer); // only 'yes','no','other'
      const route = yesNo.on_answer.find(r => r.when === norm) || yesNo.on_answer.find(r=> r.when === 'other');
      if (route) {
        executeActions(route.actions, rt);
        if (route.next) rt.currentNodeId = route.next; else rt.finished = true;
      }
    } else if (node && node.type === 'event' && event) {
      const evNode = node as EventNode;
      if (evNode.event === event) {
        executeActions(evNode.actions, rt);
        if (evNode.next) rt.currentNodeId = evNode.next; else rt.finished = true;
      }
    }
    // Después de input, avance automático (info/branch)
    advanceAuto(def, rt, nodeMap);
  }

  const finished = rt.finished;
  let triageLevel = rt.vars['triage_level'] || null;

  // Si se terminó y no hay triage pero duración >=180 => fallback rojo
  if (finished && !triageLevel && rt.vars['duration_sec'] && rt.vars['duration_sec'] >= 180) {
    triageLevel = 'ROJO';
    rt.vars['triage_level'] = triageLevel;
  }

  await sessionRef.set({
    currentNodeId: rt.currentNodeId,
    vars: rt.vars,
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    status: finished ? 'finished' : 'active',
    triageLevel: triageLevel || null,
    durationSec: rt.vars['duration_sec'] || null
  }, { merge: true });

  if (finished) {
    await db.collection('seizures').add({
      userId,
      sessionId,
      triageLevel: triageLevel || null,
      durationSec: rt.vars['duration_sec'] || null,
      vars: rt.vars,
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });
  }

  return {
    finished,
    triageLevel: triageLevel || null,
    node: finished ? null : serializeNode(nodeMap[rt.currentNodeId])
  };
});

export const finalizeSeizureSession = functions.https.onCall(async (data, context) => {
  if (!context.auth) throw new functions.https.HttpsError('unauthenticated','Auth required');
  const userId = context.auth.uid;
  const sessionId = data.sessionId as string;
  if (!sessionId) throw new functions.https.HttpsError('invalid-argument','Missing sessionId');

  const sessionRef = db.collection('flowSessions').doc(sessionId);
  const snap = await sessionRef.get();
  if (!snap.exists) throw new functions.https.HttpsError('not-found','Session not found');
  const session = snap.data()!;
  if (session.userId !== userId) throw new functions.https.HttpsError('permission-denied','Not owner');
  if (session.status !== 'finished') {
    return { triageLevelFinal: session.triageLevel || null, updated: false, reason: 'not_finished' };
  }

  const flowId = session.flowId as string;
  const version = session.version as string | undefined;
  const def = await loadFlowDefinition(flowId, version);
  const nodeMap = buildNodeMap(def);
  const finalNode = nodeMap['final_triage'];
  const vars: Record<string, any> = { ...(session.vars || {}) };
  // elapsedSec: prefer duration_sec if present else compute from timestamps
  let elapsedSec = 0;
  if (typeof vars['duration_sec'] === 'number') elapsedSec = vars['duration_sec'];
  else if (session.startedAt && session.finishedAt) {
    try {
      const startedMs = session.startedAt.toMillis ? session.startedAt.toMillis() : Date.parse(session.startedAt);
      const finishedMs = session.finishedAt.toMillis ? session.finishedAt.toMillis() : Date.parse(session.finishedAt);
      elapsedSec = Math.floor((finishedMs - startedMs)/1000);
    } catch { /* ignore */ }
  }

  // Recalcular seizures_count_24h (auditoría) contando sin incluir este resultado si ya en seizures
  let seizuresCount24h = vars['seizures_count_24h'];
  try {
    const since = Date.now() - 24*3600*1000;
    const q = await db.collection('seizures')
      .where('userId','==', userId)
      .where('createdAt','>', new Date(since)).get();
    seizuresCount24h = q.size;
    vars['seizures_count_24h'] = seizuresCount24h;
  } catch {/* ignore counting error */}

  function evaluateFinalTriage(): { triage: string, matchedCond: string } {
    if (!finalNode || finalNode.type !== 'branch') {
      // fallback lógica antigua
      if (elapsedSec >= 180) return { triage: 'ROJO', matchedCond: 'fallback_duration>=180' };
      return { triage: vars['triage_level'] || null, matchedCond: 'no_branch' } as any;
    }
    const branch = finalNode as BranchNode;
    const ctx = { vars, app: { seizures_count_24h: seizuresCount24h }, elapsedSec };
    for (const c of branch.conditions) {
      if (c.when === 'else' || evalExpr(c.when, ctx)) {
        // Buscar en acciones un record_note del triage o deducir por texto ya calculado
        let triage = vars['triage_level'];
        if (c.actions) {
          for (const act of c.actions) {
            if (act.do === 'record_note' && act.params?.field === 'triage_level') {
              triage = act.params.value;
            }
          }
        }
        // Si no vino en acciones, inferir por las mismas reglas del flujo
        if (!triage) {
            if (elapsedSec >= 180) triage = 'ROJO';
        }
        return { triage, matchedCond: c.when } as any;
      }
    }
    return { triage: vars['triage_level'] || null, matchedCond: 'none' } as any;
  }

  const { triage: recomputedTriage, matchedCond } = evaluateFinalTriage();
  const currentTriage = session.triageLevel || vars['triage_level'] || null;
  const changed = recomputedTriage && recomputedTriage !== currentTriage;

  if (changed) {
    vars['triage_level'] = recomputedTriage;
    await sessionRef.set({
      triageLevel: recomputedTriage,
      vars,
      verifiedAt: admin.firestore.FieldValue.serverTimestamp(),
      verifiedBy: 'finalize_function',
      decisionCond: matchedCond
    }, { merge: true });
    // Actualizar seizure snapshot si existe
    const seizQ = await db.collection('seizures').where('sessionId','==', sessionId).limit(1).get();
    if (!seizQ.empty) {
      await seizQ.docs[0].ref.set({ triageLevel: recomputedTriage, vars, verifiedAt: admin.firestore.FieldValue.serverTimestamp(), decisionCond: matchedCond }, { merge: true });
    }
  } else {
    await sessionRef.set({ verifiedAt: admin.firestore.FieldValue.serverTimestamp(), verifiedBy: 'finalize_function', decisionCond: matchedCond }, { merge: true });
  }

  return {
    triageLevelFinal: recomputedTriage || currentTriage,
    updated: changed,
    previous: currentTriage,
    matchedCond
  };
});

function normalizeAnswer(a: string): string {
  if (a == null) return 'other';
  if (typeof (a as any) === 'boolean') return (a as any) ? 'yes' : 'no';
  let v = String(a).trim().toLowerCase();
  v = v.replace(/[¡!?.;,]+$/g, '').trim();
  v = v.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
  v = v.replace(/\s+/g, ' ');
  // YES synonyms
  if (['si','yes','y','s','claro','affirmative','afirmativo','correcto','ok','okay'].includes(v)) return 'yes';
  // NO synonyms
  if (['no','n','nop','nope','negativo','ninguno','nah'].includes(v)) return 'no';
  // Anything else (including 'nose','no se','ns','tal vez', etc.) becomes 'other'
  return 'other';
}

function serializeNode(node?: NodeDef) {
  if (!node) return null;
  const base: any = { id: node.id, type: node.type };
  switch (node.type) {
    case 'info': return { ...base, prompt: (node as InfoNode).prompt };
    case 'yes_no': return { ...base, prompt: (node as YesNoNode).prompt };
    case 'event': return { ...base, event: (node as EventNode).event };
    case 'action': return { ...base, prompt: (node as ActionNode).prompt };
    case 'branch': return { ...base }; // nunca se envía directamente porque se resuelve server-side
  }
}
