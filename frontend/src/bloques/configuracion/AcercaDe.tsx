import type { ReactNode } from 'react';
import { CHANGELOG_4X } from './acercaDe494';

/*
 * ℹ️ About: cabecera con la versión, Highlights (en inglés, como en la 4.9.4),
 * Changelog de la 5.x (en español) y, plegado al final, el changelog de la 4.x
 * copiado del HTML original (D26, D66).
 */

interface Version {
  version: string;
  titulo: string;
  fecha: string;
  cambios: ReactNode[];
}

const HIGHLIGHTS: ReactNode[] = [
  <>One shared <b>server</b> for the whole team (PostgreSQL, HTTPS) with personal accounts and roles: <b>Admin</b>, <b>Lab User</b>, <b>Editor</b> and <b>Read only</b>.</>,
  <>Per-bench priority tables with drag &amp; drop, multi-select, duplicate and move-between-benches; benches grouped by <b>Area</b> with Area + Org filters.</>,
  <>Auto scheduling with editable/forced start, committed dates, real start/end, Vigo holidays and weekend logic.</>,
  <>Mandatory close-out window with real dates, final status, delay reason and downtime; Admin corrections are logged.</>,
  <>Redesigned general Gantt: one colour per project, parallel lanes, occupancy per bench and a project focus mode with traceability.</>,
  <>Dashboard KPIs and bench occupancy in % or hours; <b>KPI History</b> with an automatic weekly snapshot every night.</>,
  <>Billing: monthly sheet like the Excel, billed by hours with a per-test <b>Working Hours</b> calendar, and <b>Tests by person</b>.</>,
  <>Approval IDs managed in the app by an Admin.</>,
  <><b>Timing R&amp;T</b>: laboratory task management with requests, planning, technician queues, daily order, progress and hours.</>,
  <><b>Export read-only</b>: a single HTML file with the Thermal Benches plan, KPIs and history, viewable without the server.</>,
];

const CHANGELOG_5X: Version[] = [
  {
    version: '5.4', titulo: 'Exportación, KPI automáticos y migración segura', fecha: '25-nov-2026', cambios: [
      <><b>Export read-only con la interfaz nueva.</b> Descarga un único .html que se abre sin servidor, con Dashboard, By Responsible, KPI History y los bancos, <b>solo del área Thermal Benches</b>. Se puede navegar por todas las semanas del año. No lleva usuarios, correos ni datos de otras áreas.</>,
      <><b>Foto semanal de KPI automática.</b> El servidor guarda cada noche a las 23:55 la foto de la semana en curso y la del domingo la deja <b>cerrada</b>. El botón 📊 Update Yearly Status se mantiene. El Export read-only actualiza la foto sin preguntar.</>,
      <><b>KPI History muestra cómo se tomó cada foto.</b> Nueva columna «Foto» (cuándo y si fue el botón, la automática o el export) y estado de cada semana: <i>En curso</i>, <i>Cerrada</i> o <i>Provisional</i>, con aviso si alguna semana quedó provisional.</>,
      <><b>Importación desde SharePoint sin perder lo hecho en el servidor.</b> Se retira la importación que sustituía todo. La fusión comprueba que la exportación base es la correcta, no borra usuarios con perfil de Timing R&amp;T ni proyectos que use, nunca toca Timing, cuentas, horarios ni calendario, avisa de proyectos o bancos renombrados y hace una copia de seguridad antes de aplicar.</>,
      <><b>Test Benches sin saltos.</b> Al cambiar un Equipment Type, añadir un banco o moverlo con las flechas, la página ya no vuelve arriba.</>,
    ],
  },
  {
    version: '5.3', titulo: 'Timing R&T', fecha: '24-nov-2026', cambios: [
      <><b>Nueva sección Timing R&amp;T</b>, en su propia pestaña junto a TestBenchPlanner, con sus propios bancos y cámaras. Se ve con un perfil de <b>Solicitante</b>, <b>Técnico</b> o <b>Coordinador</b>; ser Admin de TestBenchPlanner no da permiso para editarla.</>,
      <><b>Vistas:</b> Mis solicitudes, Mi cola (técnico), Planificación (colas de <b>Análisis</b> y <b>Bancos</b>), Orden diario, Histórico y Configuración, con búsqueda, filtros y orden por columnas.</>,
      <><b>Ciclo de la tarea:</b> alta, asignación de técnico, registro de avances con horas y comentario, cierre con horas reales, reapertura con motivo, duplicar y cancelar. Cada cambio queda en el historial de la tarea; un técnico solo registra avances y cierra las tareas que tiene asignadas.</>,
      <><b>Importación del Excel de Timing R&amp;T</b> con vista previa, recuentos y avisos. Los datos originales del Excel se conservan en cada tarea.</>,
      <><b>Vinculación automática.</b> Las tareas importadas cuyo responsable, técnico o proyecto aún no existía se enlazan solas cuando se da de alta el usuario o el proyecto. «Datos sin vincular» permite resolver a mano los casos dudosos.</>,
      <><b>Orden diario</b> como en el Timing R&amp;T original: orden del día, cola y genéricas. El coordinador tiene un panel para reordenar la cola de cada técnico arrastrando o con flechas.</>,
      <><b>Proyectos con número y nombre por separado.</b> Configuration › Projects tiene las columnas Project Number y Name, editables por Admin y Lab User; el cambio se aplica a todos los ensayos. Los números de la serie 8 pasan al formato largo (80834 → 80000834).</>,
    ],
  },
  {
    version: '5.2', titulo: 'Facturación, áreas y roles', fecha: '18-nov-2026', cambios: [
      <><b>Billing › Monthly sheet.</b> Reproduce la hoja mensual del Excel de ensayos a partir de las tareas, con filas plegables, vista previa de lo que falta y exportación a Excel y HTML. Filtro por área.</>,
      <><b>Billing › Configuration</b> (Admin): tarifas por año y Equipment Type. Cada banco lleva su <b>Equipment Type</b> y cada proyecto su <b>Billing order</b>. Nuevas columnas Pieces, HV y Product Line en la tabla del banco.</>,
      <><b>Facturación por horas.</b> Desde el mes configurado se facturan las horas de turno reales: el setup no se factura y las paradas se descuentan.</>,
      <><b>Configuration › 🕒 Test Calendar / Working Hours</b>: por ensayo, jornada de 24, 16 u 8 h, horas de inicio y fin, fines de semana y festivos, horas de setup y paradas con hora.</>,
      <><b>Billing › 👥 Tests by person</b>: una fila por tarea y mes como la hoja INPUTS del Excel, con el Requester y filtro «Only my tests».</>,
      <><b>Áreas de bancos.</b> Nivel por encima de las categorías (Thermal Benches, HVCH - CTAG, HVCH - STC), con filtro Area + Org, menú agrupado por área y fotos de KPI por área. Un Admin o un Lab User puede crear, renombrar, ordenar y borrar áreas.</>,
      <><b>Nuevo rol Lab User</b>: todo lo de un Editor y, además, configura áreas, bancos y Working Hours. Areas, Working Hours y Monthly sheet solo los ven Admin y Lab User; un Editor solo consulta Projects.</>,
    ],
  },
  {
    version: '5.1', titulo: 'Servidor con PostgreSQL', fecha: '17-nov-2026', cambios: [
      <><b>Una sola aplicación para todo el equipo</b> en un PC servidor, con base de datos PostgreSQL. Se entra desde el navegador por <b>HTTPS</b>; ya no hace falta instalar nada en cada PC.</>,
      <><b>Inicio de sesión con cuentas propias</b>: contraseña personal (temporal la primera vez), bloqueo tras varios intentos fallidos y cierre de sesión tras 30 minutos sin actividad. Los roles se siguen gestionando en Users/Rights Management.</>,
      <><b>Servicio de Windows</b> que arranca solo con el PC, también sin sesión iniciada, y se reinicia si se cae. Copia de seguridad diaria, restauración y actualización con copia previa y vuelta al programa anterior si falla.</>,
      <><b>Guardados más seguros</b>: un cambio de varias filas se guarda entero o no se guarda, cada cambio queda registrado con la persona real y la presencia de conectados ya no tiene una hora de desfase en verano.</>,
      <><b>Calendario de festivos común</b> para todos, editable solo por un Admin.</>,
      <><b>Approval IDs gestionados desde la aplicación</b>: un Admin da de alta, cambia y borra aprobaciones (Approved, Pending, Rejected, NA) desde 📋 Approval IDs.</>,
    ],
  },
  {
    version: '5.0', titulo: 'Nueva aplicación React + Java', fecha: '16-nov-2026', cambios: [
      <><b>Reescrita</b> con interfaz React y servidor Java, con su propio Java incluido (sin Python). Misma planificación, estados, KPI y ocupación que la 4.9.4, comprobados caso a caso contra la versión anterior.</>,
      <><b>Aviso de edición simultánea</b>: si otra persona ha cambiado el mismo ensayo, la app lo avisa y deja elegir entre «Ver su versión» y «Guardar la mía encima».</>,
      <><b>Gantt general rediseñado</b> en Dashboard y By Responsible: color por proyecto (o por estado con el selector), ensayos simultáneos en carriles, ocupación de cada banco, categorías plegables, tarjeta al pasar el ratón y modo foco con ficha del proyecto y flechas de trazabilidad. El Gantt ocupa todo el alto y es la página la que se desplaza.</>,
      <><b>Correcciones respecto a la 4.9.4</b>: ✎ Corregir confirma también un cierre antiguo; la foto semanal usa siempre la semana ISO correcta; un usuario Read only ya no guarda fotos de KPI; los Admin salen primero en Users; se ve el mensaje de guardado; el foco de proyecto se recuerda.</>,
      <><b>Excel de los Gantt</b> generado por el servidor con el mismo aspecto que la 4.9.4.</>,
    ],
  },
];

export function AcercaDe({ version }: { version: string }) {
  return (
    <div className="sec" id="acerca-de">
      <div className="about-card" style={{ display: 'flex', gap: 16, alignItems: 'center', marginBottom: 14 }}>
        <img src="/ciudad.jpg" alt="" style={{ width: 120, height: 70, objectFit: 'cover', borderRadius: 10, border: '1px solid var(--line)' }} />
        <div>
          <h2 style={{ margin: 0 }}>BorgWarner STC Testing Department</h2>
          <div className="hint">Test Bench Planning &middot; Version {version} (React + Java + PostgreSQL) &middot; sucesora de la 4.9.4 (build 494, 2026-10-02)</div>
        </div>
      </div>
      <div className="card chg">
        <h3 style={{ margin: '0 0 6px' }}>Highlights</h3>
        <ul className="chg">{HIGHLIGHTS.map((h, i) => <li key={i}>{h}</li>)}</ul>
      </div>
      <div className="card chg">
        <h3 style={{ margin: '0 0 6px' }}>Changelog</h3>
        {CHANGELOG_5X.map((v) => (
          <section key={v.version}>
            <h4>v{v.version} &middot; {v.titulo} <span className="hint" style={{ fontWeight: 'normal' }}>({v.fecha})</span></h4>
            <ul className="chg">{v.cambios.map((c, i) => <li key={i}>{c}</li>)}</ul>
          </section>
        ))}
        <details style={{ marginTop: 14 }}>
          <summary style={{ cursor: 'pointer', fontWeight: 600 }}>Historial 4.x (SharePoint, v1.x – v4.9.4)</summary>
          {/* Texto estático copiado de la 4.9.4 (sin datos de usuario). */}
          <div dangerouslySetInnerHTML={{ __html: CHANGELOG_4X }} />
        </details>
      </div>
    </div>
  );
}
