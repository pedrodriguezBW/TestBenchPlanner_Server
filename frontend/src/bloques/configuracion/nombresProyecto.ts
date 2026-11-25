/*
 * Número y nombre de los proyectos (réplica de NombresProyecto.java).
 *
 * En el servidor cada proyecto guarda su nombre y su número por separado; la
 * etiqueta pública (desplegable Project, ensayos, facturación) es
 * «número - nombre». La pantalla calcula la etiqueta igual que el backend para
 * que la lista que envía coincida con la guardada.
 */

/** Formato largo de la serie 8 (8 cifras): 80834 → 80000834, 801786 → 80001786. El resto, sin cambios. */
export function numeroLargo(numero: string | null | undefined): string {
  const n = (numero ?? '').trim();
  if (!/^8[0-9]{4,8}$/.test(n)) return n;
  const resto = n.slice(1).replace(/^0+/, '');
  if (resto.length > 7) return n;
  return '8' + '0'.repeat(7 - resto.length) + resto;
}

/** El nombre todavía empieza por un número de proyecto (5 cifras o más). */
export function nombreConNumero(nombre: string | null | undefined): boolean {
  return /^\s*[0-9]{5,}(?:\s|-)/.test(nombre ?? '');
}

/** «número - nombre» escrito en el campo del nombre. */
export function tieneFormaNumeroNombre(texto: string | null | undefined): boolean {
  return /^\s*[0-9]+\s*-\s*\S/.test(texto ?? '');
}

/** Etiqueta pública: «número - nombre», o el nombre si no hay número o ya lo lleva delante. */
export function etiquetaProyecto(nombre: string, numero: string | null | undefined): string {
  const c = (numero ?? '').trim();
  const n = nombre ?? '';
  const t = n.trim();
  const yaLoLleva = !!c && t.startsWith(c) && (t.length === c.length || !/[0-9]/.test(t.charAt(c.length)));
  return !c || yaLoLleva || nombreConNumero(n) ? n : c + ' - ' + n;
}

/** Proyecto nuevo «número - nombre» (forma exacta) → número largo y nombre; si no, todo es el nombre. */
export function separarNuevo(texto: string): { numero: string; nombre: string } {
  const limpio = (texto ?? '').trim();
  const m = /^([0-9]+) - (\S(?:.*\S)?)$/.exec(limpio);
  if (!m || etiquetaProyecto(m[2], m[1]) !== limpio) return { numero: '', nombre: limpio };
  const numero = numeroLargo(m[1]);
  return etiquetaProyecto(m[2], numero) === numero + ' - ' + m[2] ? { numero, nombre: m[2] } : { numero: '', nombre: limpio };
}
