/**
 * Import function triggers from their respective submodules:
 *
 * import {onCall} from "firebase-functions/v2/https";
 * import {onDocumentWritten} from "firebase-functions/v2/firestore";
 *
 * See a full list of supported triggers at https://firebase.google.com/docs/functions
 */

import { setGlobalOptions } from "firebase-functions";
import { onRequest, onCall, HttpsError } from "firebase-functions/v2/https";
import { onDocumentCreated, onDocumentUpdated } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";
import * as admin from "firebase-admin";
import * as crypto from "node:crypto";

// Inicializa el SDK Admin (permite usar Firestore/Storage/Auth desde el server)
admin.initializeApp();

// Opciones globales: región cercana a AR, límites conservadores
setGlobalOptions({
  region: "southamerica-east1",
  maxInstances: 2,
  timeoutSeconds: 60,
  memory: "256MiB",
});

// Cloud Function HTTP: endpoint GET/POST que devuelve "pong"
export const ping = onRequest((_req, res) => {
  res.status(200).send("pong");
});

// Tipos de documentos (ligeros)
type FireTimestamp = admin.firestore.Timestamp;

interface CrisisDoc {
  id?: string;
  petId: string;
  ownerId: string;
  startedAt?: any; // Timestamp | number | Date
  durationSec?: number;
  triage?: { severity?: string; title?: string } | null;
}

interface RecommendationDoc {
  category: string; // hygiene | exercise | food | vet | medication | environment | monitoring
  title: string;
  body: string;
  evidence?: string;
  priority: number; // 1..5
  riskLevel?: "low" | "medium" | "high" | "critical";
  status: "active" | "done" | "dismissed" | "snoozed";
  validFrom: FireTimestamp;
  validTo: FireTimestamp;
  snoozeUntil?: FireTimestamp | null;
  actions?: Array<{ type: string; label: string; deepLink?: string }>;
  dedupeKey: string;
  createdAt: FireTimestamp;
  updatedAt: FireTimestamp;
  ownerId?: string;
}

// Utilidades
const db = admin.firestore();

function nowTs(): FireTimestamp { return admin.firestore.Timestamp.now(); }

function tsFromDate(d: Date): FireTimestamp { return admin.firestore.Timestamp.fromDate(d); }

function toMillis(val: any): number {
  if (val == null) return 0;
  if (val instanceof admin.firestore.Timestamp) return val.toDate().getTime();
  if (val instanceof Date) return val.getTime();
  if (typeof val === "number") {
    // Normalizar: si parece segundos, convertir a ms
    if (val > 1_000_000_000 && val < 100_000_000_000) return val * 1000;
    return val;
  }
  return 0;
}

function addHours(date: Date, h: number): Date {
  return new Date(date.getTime() + h * 60 * 60 * 1000);
}

function addDays(date: Date, d: number): Date {
  return new Date(date.getTime() + d * 24 * 60 * 60 * 1000);
}

function isoWeekKey(d: Date): string {
  // ISO week number
  const date = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()));
  const dayNum = date.getUTCDay() || 7;
  date.setUTCDate(date.getUTCDate() + 4 - dayNum);
  const yearStart = new Date(Date.UTC(date.getUTCFullYear(), 0, 1));
  const weekNo = Math.ceil((((date.getTime() - yearStart.getTime()) / 86400000) + 1) / 7);
  return `${date.getUTCFullYear()}-W${String(weekNo).padStart(2, "0")}`;
}

function monthKey(d: Date): string { return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`; }

function hashKey(s: string): string { return crypto.createHash("sha1").update(s).digest("hex"); }

function severityScore(sev?: string | null): number {
  if (!sev) return 0;
  const norm = sev.toLowerCase();
  if (["severe", "grave", "alta", "high", "3"].some(k => norm.includes(k))) return 1;
  if (["moderate", "media", "2"].some(k => norm.includes(k))) return 0.6;
  if (["mild", "leve", "1"].some(k => norm.includes(k))) return 0.3;
  return 0;
}

async function getRecentCrisesMetrics(petId: string) {
  const since30 = addDays(new Date(), -30).getTime();
  const since7 = addDays(new Date(), -7).getTime();
  const ref = db.collection("pets").doc(petId).collection("crises");
  // No todos tienen startedAt bien tipado, traemos recientes por created order client no confiable -> fetch all (limit razonable)
  const snap = await ref.get();
  let freq7 = 0, freq30 = 0, maxSev = 0, lastMs = 0, avgDur = 0, nDur = 0;
  snap.forEach(doc => {
    const c = doc.data() as CrisisDoc;
    const ms = toMillis(c.startedAt);
    if (ms <= 0) return;
    if (ms >= since30) freq30++;
    if (ms >= since7) freq7++;
    if (ms > lastMs) lastMs = ms;
    const sev = severityScore(c.triage?.severity);
    if (sev > maxSev) maxSev = sev;
    if (typeof c.durationSec === "number") { avgDur += c.durationSec; nDur++; }
  });
  const recencyH = lastMs > 0 ? (Date.now() - lastMs) / (1000 * 60 * 60) : Number.POSITIVE_INFINITY;
  const avgDurationSec = nDur > 0 ? Math.round(avgDur / nDur) : 0;
  return { freq7, freq30, maxSev, recencyH, lastMs, avgDurationSec };
}

function computeRiskPriority(metrics: { freq7: number; freq30: number; maxSev: number; recencyH: number }) {
  let score = 0;
  if (metrics.recencyH < 48) score += 0.4;
  if (metrics.freq7 >= 2) score += 0.4; else if (metrics.freq7 >= 1) score += 0.2;
  if (metrics.freq30 >= 3) score += 0.2;
  if (metrics.maxSev >= 1) score += metrics.maxSev >= 1 ? 0.4 : 0; // maxSev ya es 0..1
  score = Math.min(1, score);
  const priority = score >= 0.8 ? 5 : score >= 0.6 ? 4 : score >= 0.4 ? 3 : score >= 0.2 ? 2 : 1;
  const riskLevel: RecommendationDoc["riskLevel"] = priority >= 5 ? "critical" : priority >= 4 ? "high" : priority >= 3 ? "medium" : "low";
  return { score, priority, riskLevel };
}

async function upsertRecommendation(petId: string, ownerId: string, payload: Omit<RecommendationDoc, "createdAt" | "updatedAt" | "validFrom"> & { validFrom?: FireTimestamp }) {
  const docId = hashKey(payload.dedupeKey);
  const now = nowTs();
  const recRef = db.collection("pets").doc(petId).collection("care_recommendations").doc(docId);
  const snap = await recRef.get();
  if (!snap.exists) {
    const toCreate: RecommendationDoc = {
      ...(payload as any),
      ownerId,
      status: payload.status ?? "active",
      validFrom: payload.validFrom ?? now,
      createdAt: now,
      updatedAt: now,
    };
    await recRef.set(stripUndefinedShallow(toCreate), { merge: true });
  } else {
    const current = snap.data() as RecommendationDoc;
    // No pisar estado del usuario: si ya no está 'active', preservarlo.
    const preserveStatus = current.status && current.status !== "active";
    // Preservar snoozeUntil existente si sigue vigente; maintenance se encarga de reactivar cuando corresponde
    const preserveSnooze = current.status === "snoozed" && current.snoozeUntil != null;

    const base: Partial<RecommendationDoc> = {
      // Campos de contenido que sí queremos actualizar
      category: payload.category,
      title: payload.title,
      body: payload.body,
      evidence: payload.evidence,
      priority: payload.priority,
      riskLevel: payload.riskLevel,
      validTo: payload.validTo,
      actions: payload.actions,
      dedupeKey: payload.dedupeKey,
      ownerId,
      // No tocar validFrom si ya estaba seteado
      validFrom: current.validFrom ?? payload.validFrom ?? now,
      updatedAt: now,
    };
    if (!preserveStatus) {
      // Sólo si estaba active (o vacío), permitir actualizar estado (típicamente a 'active')
      (base as any).status = payload.status ?? current.status ?? "active";
    }
    if (!preserveSnooze) {
      // Actualizar snooze sólo si no había uno vigente
      (base as any).snoozeUntil = payload.snoozeUntil ?? current.snoozeUntil ?? null;
    }
    await recRef.set(stripUndefinedShallow(base as any), { merge: true });
  }
  return docId;
}

// Trigger: al crear una crisis
export const onCrisisCreated = onDocumentCreated("pets/{petId}/crises/{crisisId}", async (event) => {
  const petId = event.params.petId as string;
  const data = event.data?.data() as CrisisDoc | undefined;
  if (!data) return;
  try {
    const ownerId = data.ownerId;
    const metrics = await getRecentCrisesMetrics(petId);
    const { priority, riskLevel } = computeRiskPriority(metrics);
    const now = new Date();

    // Recomendación: Reposo 48h
    const restKey = `${petId}|rest_after_crisis|${event.params.crisisId}`;
    await upsertRecommendation(petId, ownerId, {
      category: "exercise",
      title: "Reposo y actividad suave por 48 horas",
      body: "Tras una crisis reciente, mantené paseos cortos y evitá juegos intensos. Observá hidratación y comportamiento.",
      evidence: metrics.lastMs ? `Crisis registrada hace ${(Math.round(((Date.now() - metrics.lastMs) / 3600000)))} h` : undefined,
      priority: Math.max(3, priority),
      riskLevel,
      status: "active",
      validTo: tsFromDate(addHours(now, 48)),
      actions: [{ type: "open_crises", label: "Ver registro" }],
      dedupeKey: restKey,
    });

    // Recomendación: Evaluación veterinaria si corresponde
    if (metrics.freq7 >= 2 || metrics.freq30 >= 3 || metrics.maxSev >= 1) {
      const vetKey = `${petId}|vet_check|week_${isoWeekKey(now)}`;
      await upsertRecommendation(petId, ownerId, {
        category: "vet",
        title: "Agendá una evaluación veterinaria",
        body: "Por la recurrencia/severidad de las crisis recientes, se sugiere consulta clínica.",
        evidence: `7d: ${metrics.freq7}, 30d: ${metrics.freq30}${metrics.maxSev >= 1 ? ", severidad alta" : ""}`,
        priority: Math.max(4, priority),
        riskLevel: priority >= 5 ? "critical" : "high",
        status: "active",
        validTo: tsFromDate(addDays(now, 14)),
        actions: [{ type: "open_professional", label: "Contactar profesional" }],
        dedupeKey: vetKey,
      });
    }

    // Recomendación: Monitoreo 48h
    const monKey = `${petId}|monitoring|${event.params.crisisId}`;
    await upsertRecommendation(petId, ownerId, {
      category: "monitoring",
      title: "Monitoreo 24-48 horas",
      body: "Registrá cualquier comportamiento inusual, apetito, hidratación y si fue necesaria medicación de rescate.",
      evidence: metrics.avgDurationSec ? `Duración promedio ${Math.round(metrics.avgDurationSec / 60)} min` : undefined,
      priority: 3,
      riskLevel: "medium",
      status: "active",
      validTo: tsFromDate(addHours(now, 48)),
      actions: [{ type: "open_crises", label: "Agregar notas" }],
      dedupeKey: monKey,
    });

    logger.info("Recommendations upserted for crisis", { petId, crisisId: event.params.crisisId });
  } catch (e) {
    logger.error("onCrisisCreated error", { petId, crisisId: event.params.crisisId, error: (e as Error).message });
    throw e;
  }
});

// Trigger: al actualizar el perfil de la mascota (planes base)
export const onPetUpdated = onDocumentUpdated("pets/{petId}", async (event) => {
  const petId = event.params.petId as string;
  const after = event.data?.after?.data() as any;
  if (!after) return;
  try {
    const ownerId = after.ownerId as string;
    // Si no cambió nada relevante, igual podemos refrescar baseline mensual sin duplicar
    const now = new Date();
    const mKey = monthKey(now);

    // Higiene mensual (genérica)
    const hygieneKey = `${petId}|hygiene|month_${mKey}`;
    await upsertRecommendation(petId, ownerId, {
      category: "hygiene",
      title: "Rutina de higiene",
      body: "Limpieza de orejas, cepillado y chequeo de uñas. Ajustá la frecuencia según raza y actividad.",
      evidence: after.breed ? `Raza: ${after.breed}` : undefined,
      priority: 2,
      riskLevel: "low",
      status: "active",
      validTo: tsFromDate(addDays(now, 30)),
      actions: [{ type: "read", label: "Ver guía" }],
      dedupeKey: hygieneKey,
    });

    // Plan de ejercicio (ajustado por crisis recientes)
    const metrics = await getRecentCrisesMetrics(petId);
    const exerciseKey = `${petId}|exercise|month_${mKey}`;
    const body = metrics.recencyH < 48
      ? "Actividad suave esta semana (10-15 min). Evitá juegos intensos tras una crisis reciente."
      : "Paseos diarios 20-40 min según edad y condición. Observá señales de fatiga.";
    await upsertRecommendation(petId, ownerId, {
      category: "exercise",
      title: "Plan de ejercicio sugerido",
      body,
      evidence: metrics.lastMs ? `Última crisis hace ${Math.round(metrics.recencyH)} h` : undefined,
      priority: metrics.recencyH < 48 ? 3 : 2,
      riskLevel: metrics.recencyH < 48 ? "medium" : "low",
      status: "active",
      validTo: tsFromDate(addDays(now, 30)),
      actions: [{ type: "open_crises", label: "Ver historial" }],
      dedupeKey: exerciseKey,
    });

    // Alimentación (placeholder básico)
    const foodKey = `${petId}|food|month_${mKey}`;
    await upsertRecommendation(petId, ownerId, {
      category: "food",
      title: "Ajuste de ración",
      body: "Mantené horarios regulares y evitá cambios bruscos. Ajustá ración según peso y actividad.",
      evidence: typeof after.weightKg === "number" ? `Peso: ${after.weightKg} kg` : undefined,
      priority: 2,
      riskLevel: "low",
      status: "active",
      validTo: tsFromDate(addDays(now, 30)),
      actions: [{ type: "open_profile", label: "Editar peso" }],
      dedupeKey: foodKey,
    });

    logger.info("Baseline recommendations upserted", { petId });
  } catch (e) {
    logger.error("onPetUpdated error", { petId, error: (e as Error).message });
    throw e;
  }
});

// Scheduler: cada 6 horas, expira recomendaciones vencidas y reactiva snooze
export const scheduledMaintenance = onSchedule({
  schedule: "every 6 hours",
  timeZone: "America/Argentina/Buenos_Aires",
}, async () => {
  const now = admin.firestore.Timestamp.now();
  const dbi = admin.firestore();

  // 1) Expirar activas con validTo pasado
  const expiredSnap = await dbi.collectionGroup("care_recommendations")
    .where("status", "==", "active")
    .where("validTo", "<=", now)
    .get();
  const batches: FirebaseFirestore.WriteBatch[] = [];
  let batch = dbi.batch();
  let count = 0;
  expiredSnap.forEach(doc => {
    batch.update(doc.ref, { status: "dismissed", updatedAt: now, terminationReason: "expired" });
    count++;
    if (count % 400 === 0) { batches.push(batch); batch = dbi.batch(); }
  });
  batches.push(batch);
  for (const b of batches) { if ((b as any) && (b as any)._ops?.length) await b.commit(); }

  // 2) Reactivar snooze vencidas (usar for..of para permitir await)
  const snoozeSnap = await dbi.collectionGroup("care_recommendations")
    .where("status", "==", "snoozed")
    .where("snoozeUntil", "<=", now)
    .get();
  let batch2 = dbi.batch();
  let count2 = 0;
  for (const doc of snoozeSnap.docs) {
    batch2.update(doc.ref, { status: "active", updatedAt: now, snoozeUntil: admin.firestore.FieldValue.delete() });
    count2++;
    if (count2 % 400 === 0) { await batch2.commit(); batch2 = dbi.batch(); }
  }
  if (count2 % 400 !== 0) await batch2.commit();

  logger.info("scheduledMaintenance done", { expired: count, reactivated: count2 });
});

// Funciones HTTPS callable
async function getLastCrisisInfo(petId: string) {
  const snap = await db.collection("pets").doc(petId).collection("crises").get();
  let lastDocId: string | null = null;
  let lastMs = 0;
  snap.forEach(d => {
    const data = d.data() as CrisisDoc;
    const ms = toMillis(data.startedAt);
    if (ms > lastMs) { lastMs = ms; lastDocId = d.id; }
  });
  return { lastDocId, lastMs };
}

async function recomputeForPet(petId: string, ownerId: string) {
  const results: { upserted: number } = { upserted: 0 };
  const now = new Date();
  const mKey = monthKey(now);

  // Baseline: higiene
  await upsertRecommendation(petId, ownerId, {
    category: "hygiene",
    title: "Rutina de higiene",
    body: "Limpieza de orejas, cepillado y chequeo de uñas. Ajustá la frecuencia según raza y actividad.",
    priority: 2,
    riskLevel: "low",
    status: "active",
    validTo: tsFromDate(addDays(now, 30)),
    actions: [{ type: "read", label: "Ver guía" }],
    dedupeKey: `${petId}|hygiene|month_${mKey}`,
  });
  results.upserted++;

  // Métricas de crisis
  const metrics = await getRecentCrisesMetrics(petId);

  // Baseline: ejercicio
  const exBody = metrics.recencyH < 48
    ? "Actividad suave esta semana (10-15 min). Evitá juegos intensos tras una crisis reciente."
    : "Paseos diarios 20-40 min según edad y condición. Observá señales de fatiga.";
  await upsertRecommendation(petId, ownerId, {
    category: "exercise",
    title: "Plan de ejercicio sugerido",
    body: exBody,
    priority: metrics.recencyH < 48 ? 3 : 2,
    riskLevel: metrics.recencyH < 48 ? "medium" : "low",
    status: "active",
    validTo: tsFromDate(addDays(now, 30)),
    actions: [{ type: "open_crises", label: "Ver historial" }],
    dedupeKey: `${petId}|exercise|month_${mKey}`,
  });
  results.upserted++;

  // Baseline: alimentación
  await upsertRecommendation(petId, ownerId, {
    category: "food",
    title: "Ajuste de alimentación",
    body: "Mantené horarios regulares y evitá cambios bruscos. Ajustá ración según peso y actividad.",
    priority: 2,
    riskLevel: "low",
    status: "active",
    validTo: tsFromDate(addDays(now, 30)),
    actions: [{ type: "open_profile", label: "Editar peso" }],
    dedupeKey: `${petId}|food|month_${mKey}`,
  });
  results.upserted++;

  // Recomendaciones por crisis reciente (si hay)
  const { lastDocId, lastMs } = await getLastCrisisInfo(petId);
  if (lastDocId && lastMs > 0) {
    const recencyH = (Date.now() - lastMs) / 3600000;
    const { priority } = computeRiskPriority({ freq7: metrics.freq7, freq30: metrics.freq30, maxSev: metrics.maxSev, recencyH });

    if (recencyH < 48) {
      await upsertRecommendation(petId, ownerId, {
        category: "exercise",
        title: "Reposo y actividad suave por 48 horas",
        body: "Tras una crisis reciente, mantené paseos cortos y evitá juegos intensos. Observá hidratación y comportamiento.",
        priority: Math.max(3, priority),
        riskLevel: priority >= 4 ? "high" : "medium",
        status: "active",
        validTo: tsFromDate(addHours(now, 48)),
        actions: [{ type: "open_crises", label: "Ver registro" }],
        dedupeKey: `${petId}|rest_after_crisis|${lastDocId}`,
      });
      results.upserted++;

      await upsertRecommendation(petId, ownerId, {
        category: "monitoring",
        title: "Monitoreo 24-48 horas",
        body: "Registrá cualquier comportamiento inusual, apetito, hidratación y si fue necesaria medicación de rescate.",
        priority: 3,
        riskLevel: "medium",
        status: "active",
        validTo: tsFromDate(addHours(now, 48)),
        actions: [{ type: "open_crises", label: "Agregar notas" }],
        dedupeKey: `${petId}|monitoring|${lastDocId}`,
      });
      results.upserted++;
    }

    if (metrics.freq7 >= 2 || metrics.freq30 >= 3 || metrics.maxSev >= 1) {
      await upsertRecommendation(petId, ownerId, {
        category: "vet",
        title: "Agendá una evaluación veterinaria",
        body: "Por la recurrencia/severidad de las crisis recientes, se sugiere consulta clínica.",
        priority: Math.max(4, priority),
        riskLevel: priority >= 5 ? "critical" : "high",
        status: "active",
        validTo: tsFromDate(addDays(now, 14)),
        actions: [{ type: "open_professional", label: "Contactar profesional" }],
        dedupeKey: `${petId}|vet_check|week_${isoWeekKey(now)}`,
      });
      results.upserted++;
    }
  }

  return results;
}

export const recomputeCare = onCall({ region: "southamerica-east1" }, async (request) => {
  try {
    const uid = request.auth?.uid;
    const petId = (request.data && (request.data.petId as string)) || "";
    if (!uid) {
      throw new HttpsError("unauthenticated", "Usuario no autenticado");
    }
    if (!petId) {
      throw new HttpsError("invalid-argument", "petId requerido");
    }
    const petSnap = await db.collection("pets").doc(petId).get();
    if (!petSnap.exists) throw new HttpsError("not-found", "Mascota no existe");
    const ownerId = petSnap.get("ownerId");
    if (ownerId !== uid) throw new HttpsError("permission-denied", "No sos el dueño de esta mascota");

    const res = await recomputeForPet(petId, ownerId);
    return { ok: true, upserted: res.upserted };
  } catch (e: any) {
    // Propagar HttpsError tal cual; envolver otros errores como INTERNAL
    if (e instanceof HttpsError) throw e;
    logger.error("recomputeCare error", { message: e?.message, stack: e?.stack });
    throw new HttpsError("internal", e?.message || "Error interno");
  }
});

export const recomputeCareHttp = onRequest({ region: "southamerica-east1" }, async (req, res) => {
  try {
    if (req.method !== "POST") {
      res.status(405).json({ error: "method-not-allowed" }); return;
    }
    const auth = req.get("Authorization") || "";
    const m = auth.match(/^Bearer (.*)$/i);
    if (!m) {
      res.status(401).json({ error: "unauthenticated", message: "Missing bearer token" }); return;
    }
    const idToken = m[1];
    let decoded: admin.auth.DecodedIdToken;
    try { decoded = await admin.auth().verifyIdToken(idToken); }
    catch (e: any) { res.status(401).json({ error: "unauthenticated", message: e?.message || "Invalid token" }); return; }

    const uid = decoded.uid;
    const petId = (req.body && (req.body.petId as string)) || "";
    if (!petId) { res.status(400).json({ error: "invalid-argument", message: "petId requerido" }); return; }

    const petSnap = await db.collection("pets").doc(petId).get();
    if (!petSnap.exists) { res.status(404).json({ error: "not-found", message: "Mascota no existe" }); return; }
    const ownerId = petSnap.get("ownerId");
    if (ownerId !== uid) { res.status(403).json({ error: "permission-denied", message: "No sos el dueño de esta mascota" }); return; }

    const result = await recomputeForPet(petId, ownerId);
    res.status(200).json({ ok: true, upserted: result.upserted });
  } catch (e: any) {
    logger.error("recomputeCareHttp error", { message: e?.message, stack: e?.stack });
    res.status(500).json({ error: "internal", message: e?.message || "Error interno" });
  }
});

// Utility: remove undefined fields (shallow) to satisfy Firestore constraints
function stripUndefinedShallow<T extends Record<string, any>>(obj: T): T {
  const out: any = {};
  for (const [k, v] of Object.entries(obj)) {
    if (v !== undefined) out[k] = v;
  }
  return out as T;
}

