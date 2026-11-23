/*
 * Actividad real de la persona en esta pestaña (hito S3, modo servidor).
 *
 * El servidor cierra la sesión tras un tiempo sin actividad, pero la interfaz
 * hace peticiones solas (relectura cada 20 s, presencia, aprobaciones…) que no
 * deben contar. Cada petición lleva en X-TBP-Inactivo los segundos desde el
 * último clic, tecla, rueda o toque en esta pestaña; el servidor solo alarga
 * la sesión con eso. Fuera del servidor la cabecera se ignora.
 */

let ultimaInteraccion = Date.now();

const anotar = () => { ultimaInteraccion = Date.now(); };

if (typeof window !== 'undefined') {
  for (const evento of ['pointerdown', 'keydown', 'wheel', 'touchstart']) {
    window.addEventListener(evento, anotar, { capture: true, passive: true });
  }
}

/** Segundos enteros desde la última interacción con esta pestaña. */
export function segundosInactivo(): number {
  return Math.max(0, Math.floor((Date.now() - ultimaInteraccion) / 1000));
}
