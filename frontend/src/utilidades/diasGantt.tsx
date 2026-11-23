import { esFinDeSemana, iso } from './fechas';

/*
 * Fila de días de la cabecera de los Gantt (Dashboard, By Responsible y cada banco).
 * Solo aparece a partir de cierto zoom (ancho de día): por debajo, la cabecera se queda
 * en meses y CW. Con poco ancho se ve solo el número; con más, la inicial y el número.
 */

/** Ancho de día (px) a partir del cual se ven los días. */
export const ANCHO_MIN_DIAS = 14;
/** Ancho de día (px) a partir del cual se ve también la inicial del día. */
const ANCHO_INICIAL = 22;

export const verDias = (anchoDia: number) => anchoDia >= ANCHO_MIN_DIAS;

export function FilaDias({ dias, W, hoy, festivo }: { dias: Date[]; W: number; hoy: Date; festivo: (d: Date) => boolean }) {
  if (!verDias(W)) return null;
  const conInicial = W >= ANCHO_INICIAL;
  return (
    <div className={`g-days${conInicial ? '' : ' g-days-compacta'}`}><div className="g-label" />{dias.map((d) => (
      <div key={d.getTime()} className={`g-day ${esFinDeSemana(d) ? 'we' : ''} ${festivo(d) ? 'hol' : ''} ${d.getTime() === hoy.getTime() ? 'today' : ''}`}
        style={{ width: W, minWidth: W }} title={iso(d)}>
        {conInicial ? <>{'MTWTFSS'[(d.getDay() + 6) % 7]}<br />{d.getDate()}</> : d.getDate()}
      </div>
    ))}</div>
  );
}
