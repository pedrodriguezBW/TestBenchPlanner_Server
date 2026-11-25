import { lazy, Suspense, useEffect, useRef, useState, type ReactNode } from 'react';
import { api } from './api/cliente';
import { useConservarScroll } from './estado/useConservarScroll';
import { VistaBanco } from './bloques/banco/VistaBanco';
import { exportarSoloLectura } from './bloques/exportaciones/descargas';
import { fotoActiva } from './api/foto';
import { VistaDashboard } from './bloques/panel/VistaDashboard';
import { VistaResponsable } from './bloques/panel/VistaResponsable';
import { VistaKpiHistorico } from './bloques/kpi/VistaKpiHistorico';
import { ListaEquipos } from './bloques/recursos/ListaEquipos';
import { AcercaDe } from './bloques/configuracion/AcercaDe';
import { ConfigBancos } from './bloques/configuracion/ConfigBancos';
import { GestorAreas } from './bloques/configuracion/GestorAreas';
import { HorarioEnsayos } from './bloques/configuracion/HorarioEnsayos';
import { ConfigHerramientas } from './bloques/configuracion/ConfigHerramientas';
import { ConfigProyectos } from './bloques/configuracion/ConfigProyectos';
import { ConfigUsuarios } from './bloques/configuracion/ConfigUsuarios';
import { RegistroActividad } from './bloques/configuracion/RegistroActividad';
import { VistaRecursos } from './bloques/recursos/VistaRecursos';
import { VistaHojaMensual } from './bloques/facturacion/VistaHojaMensual';
import { VistaRegistroFacturacion } from './bloques/facturacion/VistaRegistroFacturacion';
import { VistaConfiguracionFacturacion } from './bloques/facturacion/VistaConfiguracionFacturacion';
import { enlaceTiming, leerRutaTiming, vistaInicialTiming, vistasTiming, VISTAS_TIMING } from './bloques/timing/modelo';
import { Avisos, DialogoConflicto } from './componentes/Avisos';
import { AvisoSinConexion, AvisoVersion, Bienvenida, Presencia } from './componentes/Colaboracion';
import { usePlanificador, type EstadoSincronizacion } from './estado/Planificador';
import { useColaConfiguracion } from './estado/useColaConfiguracion';
import { TIPOS_DE_ENSAYO } from './utilidades/textos';
import { codigosArea, nombresArea, normalizarArea, type CodigoArea } from './utilidades/areas';
import { usePreferencia } from './utilidades/preferencias';
import { dia, fmt, semanaIso } from './utilidades/fechas';
import type { Vista } from './api/tipos';

/*
 * Estructura de la aplicación: barra superior (presencia, avisos), menú
 * lateral y la pantalla elegida.
 */

const VERSION = '5.4.0';
const WorkspaceTiming = lazy(() => import('./bloques/timing/WorkspaceTiming').then((m) => ({ default: m.WorkspaceTiming })));

const SINCRONIZACION: Record<EstadoSincronizacion, [string, string]> = {
  ok: ['\u25CF Synced', 'var(--ok)'],
  guardando: ['\u21BB Saving\u2026', 'var(--warn)'],
  error: ['\u26A0 Sin backend', 'var(--danger)'],
  conectando: ['\u25CB Conectando\u2026', 'var(--muted)'],
  bloqueado: ['\uD83D\uDD12 Solo lectura (sin conexion)', 'var(--danger)'],
};

function leerRuta(): string {
  return window.location.hash;
}

function rutaPlanner(hash: string): string {
  if (leerRutaTiming(hash)) return '';
  try {
    return decodeURIComponent(hash.replace(/^#\/?/, ''));
  } catch {
    return '';
  }
}

function guardarRuta(clave: string, ruta: string) {
  try { window.sessionStorage.setItem(clave, ruta); } catch { /* El navegador puede desactivar el almacenamiento. */ }
}

function recuperarRuta(clave: string, defecto: string) {
  try { return window.sessionStorage.getItem(clave) || defecto; } catch { return defecto; }
}

const PANTALLAS_CONFIGURACION = ['__benches__', '__areas__', '__horario__', '__rescfg__', '__projects__', '__users__', '__billing_config__'];

export function App() {
  const { vista, sesion, soloLectura, sincronizacion, copiaSinConexion, proyectos, personas } = usePlanificador();
  // 📤 Foto de solo lectura: solo Dashboard, By Responsible, KPI History y los bancos de Thermal Benches.
  const foto = fotoActiva();
  const [actual, setActual] = useState(leerRuta);

  useEffect(() => {
    const alCambiar = () => setActual(leerRuta());
    window.addEventListener('hashchange', alCambiar);
    return () => window.removeEventListener('hashchange', alCambiar);
  }, []);
  useEffect(() => {
    const destino = recuperarRuta('tbp-timing-destino', '');
    if (!destino) return;
    guardarRuta('tbp-timing-destino', '');
    const ruta = leerRutaTiming(destino);
    if (!window.location.hash && ruta && !ruta.invalida) window.location.replace(destino);
  }, []);

  const ir = (destino: string) => { window.location.hash = '/' + encodeURIComponent(destino); };
  const bancos = vista ? vista.grupos.flatMap((g) => g.bancos.map((b) => b.nombre)) : [];
  // Sin ruta se abre el Dashboard, como en la 4.9.4.
  const elegida = rutaPlanner(actual) || '__dash__';
  const pantalla = foto && !['__dash__', '__resp__', '__kpihist__'].includes(elegida) && !bancos.includes(elegida) ? '__dash__' : elegida;
  const rutaTiming = leerRutaTiming(actual);
  const vistas = sesion ? vistasTiming(sesion) : [];
  const puedeTiming = vistas.length > 0;
  const vistaTiming = rutaTiming?.vista ?? vistaInicialTiming(vistas);
  const timingValido = !!rutaTiming && !rutaTiming.invalida && vistas.includes(vistaTiming);
  const [ultimaPlanner, setUltimaPlanner] = useState(() => recuperarRuta('tbp-ultima-planner', '#/'));
  const [ultimaTiming, setUltimaTiming] = useState(() => recuperarRuta('tbp-ultima-timing', enlaceTiming()));
  const vistaRecordada = leerRutaTiming(ultimaTiming)?.vista;
  const destinoTiming = vistaRecordada && vistas.includes(vistaRecordada) ? ultimaTiming : enlaceTiming();
  useEffect(() => {
    if (timingValido) {
      const enlace = enlaceTiming(vistaTiming, rutaTiming?.id);
      setUltimaTiming(enlace);
      guardarRuta('tbp-ultima-timing', enlace);
    }
  }, [timingValido, vistaTiming, rutaTiming?.id]);
  useEffect(() => {
    const pantallas = ['__dash__', '__resp__', '__kpihist__', '__resources__', '__rescfg__', '__benches__', '__areas__',
      '__horario__', '__projects__', '__users__', '__tools__', '__about__', '__activity__',
      '__billing_sheet__', '__billing_registro__', '__billing_config__'];
    if (!rutaTiming && (pantallas.includes(pantalla) || vista?.grupos.some((g) => g.bancos.some((b) => b.nombre === pantalla)))) {
      setUltimaPlanner(actual || '#/');
      guardarRuta('tbp-ultima-planner', actual || '#/');
    }
  }, [actual, pantalla, vista, rutaTiming]);
  // Hotfix: las pantallas de configuración no vuelven arriba al guardar un cambio.
  const principal = useRef<HTMLElement>(null);
  useConservarScroll(principal, PANTALLAS_CONFIGURACION.includes(pantalla));
  const esAdmin = !!sesion?.esAdmin;
  // Lab User (D55): todo lo de un Editor y, además, la gestión de las áreas, los bancos (D58) y el horario de los ensayos (D59).
  const esLabUser = sesion?.rol === 'labuser';
  const esEditor = sesion?.rol === 'editor' || esLabUser;
  // La configuración (Test Benches, Projects) se guarda en segundo plano: mientras tanto, «Saving…».
  const configuracionPendiente = useColaConfiguracion().pendientes > 0;
  const [textoSync, colorSync] = SINCRONIZACION[configuracionPendiente && sincronizacion === 'ok' ? 'guardando' : sincronizacion];
  const idsUsados = vista ? [...new Set(Object.values(vista.bancos).flatMap((fs) => fs.map((f) => String(f.datos.approvalId ?? ''))).filter(Boolean))] : [];
  const proyectosUsados = vista ? [...new Set([...proyectos, ...Object.values(vista.bancos).flatMap((fs) => fs.map((f) => String(f.datos.project ?? '')))].filter(Boolean))] : proyectos;

  /**
   * 📤 Export read-only (D64): primero actualiza, sin preguntar, la foto semanal
   * de KPI de esta semana (quien no puede editar exporta lo ya guardado), para
   * que el fichero lleve el histórico al día.
   */
  const exportarFotoSoloLectura = async () => {
    if (!vista) return;
    try {
      if (!soloLectura) await api.fotoSemanal('export').catch(() => undefined);
      await exportarSoloLectura(VERSION);
    } catch (e) {
      window.alert(`Could not export read-only snapshot: ${e instanceof Error ? e.message : String(e)}`);
    }
  };

  const pestana = (clave: string, contenido: ReactNode) => (
    <div key={clave} className={`tab ${pantalla === clave ? 'active' : ''}`} onClick={() => ir(clave)}>{contenido}</div>
  );
  const olvidarRutas = () => {
    guardarRuta('tbp-ultima-planner', '');
    guardarRuta('tbp-ultima-timing', '');
  };

  return (
    <div className={[soloLectura && !rutaTiming ? 'ro' : '', foto ? 'foto' : '', puedeTiming ? 'tbp-dos-apps' : '', rutaTiming ? 'timing-shell' : ''].join(' ')}>
      <header className={puedeTiming ? 'topbar topbar-apps' : 'topbar'}>
        <div className="logo-chip"><img src="/logo.png" alt="BorgWarner" /></div>
        {puedeTiming && <nav className="apps-selector" aria-label="Aplicaciones">
          <a href={ultimaPlanner} aria-current={!rutaTiming ? 'page' : undefined}>TestBenchPlanner</a>
          <a href={rutaTiming && timingValido ? actual : destinoTiming} aria-current={timingValido ? 'page' : undefined}>Timing R&amp;T</a>
        </nav>}
        {!rutaTiming && <h1 className={puedeTiming ? 'topbar-cuenta-rol' : undefined}>
          {!puedeTiming && 'Test Bench Planning'} <span className="build-tag">v{VERSION}</span>
          {sesion && !rutaTiming && <span className={`role-pill ${sesion.rol}`}>{sesion.rol === 'readonly' ? 'Read only' : sesion.rol === 'labuser' ? 'Lab User' : sesion.rol}</span>}
        </h1>}
        <div className="spacer" />
        {!foto && !sesion?.modoServidor && <a className="btn ghost sm no-print" href="/clasico" title="Interfaz 4.9.4 (respaldo)">Versión clásica</a>}
        {!rutaTiming && !foto && <Presencia activa={!!sesion && !copiaSinConexion} />}
        {esAdmin && vista && !rutaTiming && !foto && (
          <button onClick={() => void exportarFotoSoloLectura()} title="Exportar una foto HTML de solo lectura para el proveedor">&#128228; Export read-only</button>
        )}
        {!rutaTiming && !foto && <span id="sp-status" style={{ fontSize: 12, fontWeight: 600, color: colorSync, margin: '0 8px', whiteSpace: 'nowrap' }}>{textoSync}</span>}
        {!rutaTiming && <button onClick={() => window.print()}>&#128424; Print</button>}
        {sesion?.modoServidor && !foto && (rutaTiming ? <details className="timing-cuenta no-print">
          <summary id="cuenta-nombre" title={sesion.email}>{sesion.nombre || sesion.email}</summary>
          <div className="timing-cuenta-menu">
            <a href="/cambiar-clave">Cambiar contraseña</a>
            <button type="button" onClick={() => window.print()}>Imprimir</button>
            <form method="post" action="/logout" onSubmit={olvidarRutas}>
              <button type="submit">Cerrar sesión</button>
            </form>
          </div>
        </details> : (
          <span className="no-print" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, marginLeft: 8 }}>
            <span id="cuenta-nombre" title={sesion.email} style={{ fontSize: 12, opacity: 0.85, whiteSpace: 'nowrap' }}>&#128100; {sesion.nombre || sesion.email}</span>
            <a className="btn ghost sm" id="cuenta-clave" href="/cambiar-clave" title="Cambiar tu contraseña de TestBenchPlanner">&#128273; Password</a>
            <form method="post" action="/logout" style={{ display: 'inline', margin: 0 }} onSubmit={olvidarRutas}>
              <button type="submit" className="btn ghost sm" id="cuenta-salir" title="Cerrar la sesión">Log out</button>
            </form>
          </span>
        ))}
      </header>
      {!foto && <AvisoVersion version={VERSION} />}
      {foto && (
        <div className="ro-banner" id="ro-banner">&#128274; <b>READ-ONLY snapshot</b> &middot; Thermal Benches &middot; generated {fmt(dia(foto.generada) ?? new Date())}
          {' '}{foto.generada.slice(11, 16)} (CW{semanaIso(dia(foto.generada) ?? new Date())}) &middot; data is embedded and cannot be changed</div>
      )}
      {!rutaTiming && sincronizacion === 'bloqueado' && (
        <div id="lock-banner" style={{ display: 'block' }}>&#128274; <b>SIN CONEXI&Oacute;N CON SHAREPOINT</b> &middot; no se ha podido leer el servidor,
          as&iacute; que la app est&aacute; en <b>solo lectura</b> para proteger los datos. <b>No se guardar&aacute; nada.</b> Cierra y vuelve a abrir cuando tengas sesi&oacute;n.</div>
      )}
      {!rutaTiming && <AvisoSinConexion copia={copiaSinConexion} />}
      {!rutaTiming && !foto && <Bienvenida sesion={sesion} />}
      {rutaTiming ? <div className="layout timing-layout">
        {timingValido && sesion ? <>
          <nav className="timing-menu" aria-label="Timing R&T">
            {vistas.map((v) => <a key={v} href={enlaceTiming(v)}
              aria-current={v === vistaTiming ? 'page' : undefined}>{VISTAS_TIMING[v]}</a>)}
          </nav>
          <Suspense fallback={<main id="main"><p className="hint">Cargando Timing R&amp;T…</p></main>}>
            <WorkspaceTiming vista={vistaTiming} id={rutaTiming.id} sesion={sesion} />
          </Suspense>
        </> : <main id="main"><section className="card">
          <h2>Timing R&amp;T</h2>
          <p role="status">{!sesion && sincronizacion === 'conectando' ? 'Comprobando acceso…' :
            !puedeTiming ? 'Tu cuenta no tiene acceso a Timing R&T.' : 'Esta vista no existe o tu perfil no permite abrirla.'}</p>
          <a className="btn ghost" href={puedeTiming ? enlaceTiming() : ultimaPlanner}>{puedeTiming ? 'Volver a Timing R&T' : 'Volver a TestBenchPlanner'}</a>
        </section></main>}
      </div> : <div className="layout">
        <nav className="side" id="side">
          <div className="menu-sec" style={{ marginTop: 0, borderTop: 'none', paddingTop: 0 }}>Overview</div>
          {pestana('__dash__', <>&#128202; Dashboard</>)}
          {pestana('__resp__', <>&#128100; By Responsible</>)}
          {pestana('__kpihist__', <>&#128200; KPI History</>)}
          {vista && <MenuBancos vista={vista} pestana={pestana} />}
          {!foto && <>
          <div className="grp-title">Additional Equipment</div>
          {pestana('__resources__', <>&#128203; List of Equipments</>)}
          {/* Facturación: módulo independiente de la planificación y de Settings. */}
          <div className="grp-title">Billing</div>
          {(esAdmin || esLabUser) && pestana('__billing_sheet__', <>&#128182; Monthly sheet</>)}
          {pestana('__billing_registro__', <>&#128101; Tests by person</>)}
          {esAdmin && pestana('__billing_config__', <>&#128296; Configuration</>)}
          <div className="grp-title">Configuration</div>
          {(esAdmin || esLabUser) && pestana('__benches__', <>&#128296; Test Benches</>)}
          {(esAdmin || esLabUser) && pestana('__areas__', <>&#128450;&#65039; Areas</>)}
          {(esAdmin || esLabUser) && pestana('__horario__', <>&#128338; Test Calendar / Working Hours</>)}
          {esAdmin && pestana('__rescfg__', <>&#128296; Equipments List</>)}
          {(esAdmin || esEditor) && pestana('__projects__', <>&#128193; Projects</>)}
          {esAdmin && pestana('__users__', <>&#128100; Users/Rights Management</>)}
          {esAdmin && pestana('__tools__', <>&#128295; Tools</>)}
          {pestana('__about__', <>&#8505;&#65039; About</>)}
          {esAdmin && pestana('__activity__', <>&#128220; Activity log</>)}
          </>}
        </nav>
        <main id="main" ref={principal}>
          {!vista && sincronizacion !== 'bloqueado' && <div className="empty">Conectando&hellip;</div>}
          {vista && bancos.includes(pantalla) && (
            <div className="tbp-sin-marco"><VistaBanco banco={pantalla} onIrABanco={ir} /></div>
          )}
          {vista && pantalla === '__dash__' && <VistaDashboard />}
          {vista && pantalla === '__resp__' && <VistaResponsable />}
          {vista && pantalla === '__kpihist__' && <VistaKpiHistorico />}
          {vista && pantalla === '__resources__' && <VistaRecursos />}
          {vista && pantalla === '__rescfg__' && (esAdmin ? <ListaEquipos /> : <VistaDashboard />)}
          {/* Configuración (bloque E): las pantallas de Admin llevan al Dashboard si no se es Admin, como en la 4.9.4. */}
          {/* Test Benches: Admin o Lab User (D58). */}
          {vista && pantalla === '__benches__' && (esAdmin || esLabUser ? <ConfigBancos /> : <VistaDashboard />)}
          {vista && pantalla === '__projects__' && (esAdmin || esEditor ? <ConfigProyectos /> : <VistaDashboard />)}
          {/* Áreas (D56, D62): solo Admin y Lab User (las gestionan solo con PostgreSQL). */}
          {vista && pantalla === '__areas__' && (esAdmin || esLabUser ? <div className="sec" style={{ maxWidth: 1100 }}><GestorAreas /></div> : <VistaDashboard />)}
          {/* Horario de los ensayos (D59, D62): solo Admin y Lab User, y solo con PostgreSQL. */}
          {vista && pantalla === '__horario__' && (esAdmin || esLabUser ? <HorarioEnsayos /> : <VistaDashboard />)}
          {vista && pantalla === '__users__' && (esAdmin ? <ConfigUsuarios /> : <VistaDashboard />)}
          {vista && pantalla === '__tools__' && (esAdmin ? <ConfigHerramientas /> : <VistaDashboard />)}
          {vista && pantalla === '__activity__' && (esAdmin ? <RegistroActividad /> : <VistaDashboard />)}
          {vista && pantalla === '__about__' && <AcercaDe version={VERSION} />}
          {vista && pantalla === '__billing_sheet__' && (esAdmin || esLabUser ? <VistaHojaMensual /> : <VistaDashboard />)}
          {/* Registro del año como la hoja INPUTS del Excel: solo consulta, todos. */}
          {vista && pantalla === '__billing_registro__' && <VistaRegistroFacturacion />}
          {vista && pantalla === '__billing_config__' && (esAdmin ? <VistaConfiguracionFacturacion /> : <VistaDashboard />)}
        </main>
      </div>}
      <datalist id="dl-types">{TIPOS_DE_ENSAYO.map((t) => <option key={t} value={t} />)}</datalist>
      <datalist id="dl-resp">{personas.map((p) => <option key={p} value={p} />)}</datalist>
      <datalist id="dl-proj">{proyectosUsados.map((p) => <option key={p} value={p} />)}</datalist>
      <datalist id="dl-ids">{idsUsados.map((p) => <option key={p} value={p} />)}</datalist>
      <Avisos />
      <DialogoConflicto />
    </div>
  );
}

/**
 * Bancos del menú lateral agrupados por área (Thermal Benches, HVCH - CTAG,
 * HVCH - STC y las creadas con PostgreSQL) y, dentro, por categoría. Arriba, un desplegable para ver todas
 * las áreas o solo una; cada área se pliega con un clic en su título. Las dos
 * cosas se recuerdan en este navegador y no dependen del filtro del Dashboard.
 * Un área sin categorías no aparece en el menú (sí en Configuration › Test Benches).
 */
function MenuBancos({ vista, pestana }: { vista: Vista; pestana: (clave: string, contenido: ReactNode) => ReactNode }) {
  const [filtro, setFiltro] = usePreferencia<'ALL' | CodigoArea>('tbp_menu_area', 'ALL');
  const [plegadas, setPlegadas] = usePreferencia<Record<string, boolean>>('tbp_menu_areas_plegadas', {});
  const nombres = nombresArea(vista);
  const conCategorias = codigosArea(vista).filter((c) => vista.grupos.some((g) => normalizarArea(g.area, vista) === c));
  const elegido = filtro !== 'ALL' && !conCategorias.includes(filtro) ? 'ALL' : filtro;
  const visibles = conCategorias.filter((c) => elegido === 'ALL' || c === elegido);
  return (
    <div className="menu-bancos">
      <div className="menu-sec">Test benches</div>
      {conCategorias.length > 0 && !fotoActiva() && (
        <select className="area-sel no-print" data-solo-nueva="" value={elegido} title="Show the benches of one area"
          onChange={(e) => setFiltro(e.target.value as 'ALL' | CodigoArea)}>
          <option value="ALL">All areas</option>
          {conCategorias.map((c) => <option key={c} value={c}>{nombres[c]}</option>)}
        </select>
      )}
      {visibles.map((c) => {
        const grupos = vista.grupos.filter((g) => normalizarArea(g.area, vista) === c);
        const abierta = !plegadas[c];
        const numBancos = grupos.reduce((n, g) => n + g.bancos.length, 0);
        return (
          <div key={c} className="area-menu" data-area={c}>
            <div className="area-title" data-solo-nueva="" role="button" title={abierta ? 'Collapse' : 'Expand'}
              onClick={() => setPlegadas({ ...plegadas, [c]: abierta })}>
              <span className="flecha">{abierta ? '\u25BE' : '\u25B8'}</span>{nombres[c]}
              <span className="area-num" title={`${numBancos} benches`}>{numBancos}</span>
            </div>
            {abierta && (
              <div className="area-body">
                {grupos.map((g) => (
                  <div key={g.clave}>
                    <div className="grp-title" style={{ borderLeftColor: g.color }}>{g.nombre}</div>
                    {g.bancos.map((b) => pestana(b.nombre, <><span className="dot" style={{ background: g.color }} />{b.nombre}</>))}
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
