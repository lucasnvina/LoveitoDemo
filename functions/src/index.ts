/**
 * Import function triggers from their respective submodules:
 *
 * import {onCall} from "firebase-functions/v2/https";
 * import {onDocumentWritten} from "firebase-functions/v2/firestore";
 *
 * See a full list of supported triggers at https://firebase.google.com/docs/functions
 */

import { setGlobalOptions } from "firebase-functions";
import { onRequest, onCall } from "firebase-functions/v2/https";
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
  const base: Partial<RecommendationDoc> = {
    ...payload,
    ownerId,
    validFrom: payload.validFrom ?? now,
    updatedAt: now,
  };
  if (!snap.exists) {
    await recRef.set({ ...base, createdAt: now } as RecommendationDoc, { merge: true });
  } else {
    await recRef.set(base, { merge: true });
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
    title: "Ajuste de ración",
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
  const uid = request.auth?.uid;
  const petId = (request.data && (request.data.petId as string)) || "";
  if (!uid) {
    throw new Error("UNAUTHENTICATED");
  }
  if (!petId) {
    throw new Error("INVALID_ARGUMENT: petId requerido");
  }
  const petSnap = await db.collection("pets").doc(petId).get();
  if (!petSnap.exists) throw new Error("NOT_FOUND: mascota no existe");
  const ownerId = petSnap.get("ownerId");
  if (ownerId !== uid) throw new Error("PERMISSION_DENIED");

  const res = await recomputeForPet(petId, ownerId);
  return { ok: true, upserted: res.upserted };
});
