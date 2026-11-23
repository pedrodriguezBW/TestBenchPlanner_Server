/*
 * Tipos de la API /api/v1 del backend Java. Reflejan exactamente lo que
 * devuelve RepresentacionVista: los datos del ensayo tal cual (DataJson) y,
 * aparte, todo lo calculado por el dominio. La interfaz no recalcula reglas.
 */

/** Campos del ensayo guardados en DataJson (tipos mezclados, como en la 4.9.4). */
export interface DatosEnsayo {
  id: string;
  done?: boolean;
  type?: string;
  resp?: string;
  project?: string;
  comments?: string;
  approvalId?: string;
  avail?: string;
  weekend?: boolean;
  inputs?: boolean;
  durMode?: string;
  durManual?: number | string;
  manualUnit?: string;
  cycles?: number | string;
  cycleVal?: number | string;
  cycleUnit?: string;
  cf?: number | string;
  startLock?: boolean;
  startOverride?: string;
  endLock?: boolean;
  endOverride?: string;
  confirmedStart?: string;
  confirmedEnd?: string;
  actualStart?: string;
  trueEnd?: string;
  progressActual?: number | string;
  lastUpdate?: string;
  manualState?: string;
  delayReason?: string;
  downtimeDays?: number | string;
  downtimeNote?: string;
  resources?: string[];
  histStart?: string;
  histEnd?: string;
  doneDate?: string;
  [campo: string]: unknown;
}

export interface Solape {
  etiqueta: string;
  inicio: string;
  fin: string;
}

export interface Sugerencia {
  clave: 'noprog' | 'endlate' | 'startlate' | 'slow';
  dias?: number;
  fecha: string | null;
  referencia: string | null;
}

export type EstadoEnsayo = 'queued' | 'confirmed' | 'running' | 'delayed' | 'completed' | 'special';

/** Resultado del dominio para un ensayo (activo o cerrado). */
export interface Calculo {
  estado: EstadoEnsayo;
  estadoDeclarado: 'ok' | 'delayed';
  diasNominales: number;
  diasPlanificados: number;
  fraccionAvance: number;
  tipoEspecial: boolean;
  // Solo activos
  inicio?: string | null;
  fin?: string | null;
  criterioInicio?: string;
  arranqueEfectivo?: string;
  arranqueAsumido?: boolean;
  finTolerado?: string | null;
  finPlanificado?: string | null;
  diasSinAvance?: number;
  solapes?: Solape[];
  sugerencias?: Sugerencia[];
  sugerenciaAbierta?: boolean;
  // Solo cerrados
  estadoFinal?: 'ok' | 'delayed';
  cierreConfirmado?: boolean;
}

export interface FilaEnsayo {
  id: string;
  version: string | null;
  cerrado: boolean;
  /** Alta o copia que ya se enseña mientras el backend la guarda en SharePoint. */
  optimista?: boolean;
  prioridad?: number;
  datos: DatosEnsayo;
  calculo: Calculo;
}

export interface Grupo {
  clave: string;
  nombre: string;
  color: string;
  /** Área de la categoría (THERMAL, HVCH-CTAG, HVCH-STC o una creada con PostgreSQL, D56). */
  area?: string;
  bancos: { nombre: string; organizacion: string; area?: string }[];
}

/** Un área de bancos (nivel por encima de las categorías), en su orden. */
export interface Area {
  clave: string;
  nombre: string;
  /** La organización se elige banco a banco (BW / CTAG), como en Thermal Benches. */
  conOrganizacion: boolean;
  /** Organización fija de sus bancos (null = banco a banco; ausente con un backend anterior). */
  organizacionFija?: string | null;
}

/** Un área en la gestión de áreas (GET /api/v1/areas/gestion, D56). */
export interface AreaGestion {
  code: string;
  name: string;
  /** Organización fija; null = banco a banco. */
  organization: string | null;
  order: number;
  /** Bancos que tiene ahora (con bancos no se puede borrar). */
  benches: number;
}

export interface Vista {
  hoy: string;
  semanaHoy: number;
  calendario: { anio: number; saltarFestivos: boolean; festivos: { fecha: string; nombre: string }[] };
  /** Las áreas en su orden, aunque no tengan categorías (ausente con un backend anterior). */
  areas?: Area[];
  grupos: Grupo[];
  bancos: Record<string, FilaEnsayo[]>;
  /** Sin conexión: fecha (ISO) de la copia de este PC que se está mostrando. */
  copiaSinConexion?: string;
}

/** Persona conectada (burbuja de presencia, /api/v1/presencia). */
export interface Conectado {
  email: string;
  name: string;
  initials: string;
  color: string;
  me: boolean;
}

export type PerfilTiming = 'solicitante' | 'tecnico' | 'coordinador';

export interface Sesion {
  email: string;
  nombre: string;
  responsable: string;
  /** labuser: Lab User (D55), un Editor que además gestiona las áreas, los bancos (D58) y el horario de los ensayos (D59). */
  rol: 'admin' | 'editor' | 'labuser' | 'readonly';
  esAdmin: boolean;
  soloLectura: boolean;
  copiaSinConexion?: string;
  /** Hito S2: la aplicación corre en el servidor y es compartida (sin /clasico ni datos «de este PC»). */
  modoServidor?: boolean;
  timingRoles?: PerfilTiming[];
}

/** Estado de la cuenta (contraseña) de una persona de Users/Rights Management (modo servidor, hito S3). */
export interface EstadoCuenta {
  email: string;
  tieneClave: boolean;
  debeCambiar: boolean;
  intentosFallidos: number;
  /** ISO; solo si está bloqueada ahora. */
  bloqueadaHasta: string | null;
  ultimoAcceso: string | null;
  cambiadaEn: string | null;
}

export interface Aprobacion {
  id: number;
  status: string;
  color: string;
  raw: string | null;
  requester: string | null;
  service: string | null;
}

/** Lo que se edita de una aprobación (Global status, Requester name, Service details). */
export interface DatosAprobacion {
  raw: string;
  requester: string;
  service: string | null;
}

/** Valores de la ventana de cierre (Cierre.Valores en el backend). */
export interface ValoresCierre {
  arranqueReal: string;
  finReal: string;
  estadoFinal: string;
  motivo: string;
  explicacion: string;
  diasParada: string;
  notaParada: string;
}

export interface ValidacionCierre {
  error: string;
  desviacion: number | null;
  sugerirRetraso: boolean;
  diasCalendario: number;
  diasFacturables: number | null;
}

export interface PropuestaCierre {
  valores: ValoresCierre;
  validacion: ValidacionCierre;
  comprometidoInicio: string;
  comprometidoFin: string;
}

/** Fila en conflicto devuelta con un 409. */
export interface FilaEnConflicto {
  id: string;
  version: string | null;
  borrado: boolean;
  datos: DatosEnsayo | null;
}

/** Respuesta de cualquier operación: la vista ya actualizada. */
export interface RespuestaOperacion {
  resultado?: string;
  filasGuardadas: number;
  vista: Vista;
}

/** Tipos de operación de banco que acepta /bancos/{banco}/operaciones. */
export type TipoOperacion =
  | 'comprometer' | 'deshacerCompromiso' | 'comprometerTodos' | 'estado' | 'aplicarSugerencia'
  | 'reordenar' | 'duplicar' | 'borrarSeleccion' | 'borrar' | 'mover' | 'anadir'
  | 'alternarRecurso' | 'restaurar' | 'reutilizar';

/* ===== Dashboard (bloque B) ============================================ */

/** Un bloque KPI («Esta semana» / «Semana que viene»), nombres de la 4.9.4. */
export interface BloqueKpi {
  total: number;
  benches: number;
  accr: number;
  delayed: number;
  sug: number;
}

export interface BloquesKpi {
  estaSemana: BloqueKpi;
  siguiente: BloqueKpi;
}

export interface FilaOcupacion {
  clave: string;
  nombre: string;
  color: string;
  bancos: number;
  trabajando: number;
  inicia: number;
  acaba: number;
  busy: number;
  cap: number;
  pct: number;
}

export interface TablaOcupacion {
  grupos: FilaOcupacion[];
  busy: number;
  cap: number;
  pct: number;
}

export interface SerieTendencia {
  clave: string;
  nombre: string;
  color: string;
  bancos: number;
  busy: number[];
  pct: number[];
}

export interface Tendencia {
  anio: number;
  semanasDelAnio: number;
  desde: number;
  hasta: number;
  lunes: string[];
  cw: number[];
  series: SerieTendencia[];
}

/** Una fila de la foto semanal (📊 Update Yearly Status). */
export interface FotoSemanal {
  year: number;
  cw: number;
  org: string;
}

/* ===== KPI History (bloque C) ========================================== */

/** Una semana del histórico (kpiHistRows de la 4.9.4). */
export interface SemanaHistorico {
  year: number;
  cw: number;
  org: string;
  benches: number;
  total: number | null;
  starts: number;
  ends: number;
  running: number;
  noid: number;
  behind: number;
  delayed: number;
  risk: number;
  occPct: number | null;
  occBusy: number | null;
  occCap: number | null;
  isNew: boolean;
  unplanned: number;
  reasons: Record<string, number>;
}

export interface Historico {
  /** Filas leídas de la lista (todas las organizaciones). */
  filas: number;
  semanas: SemanaHistorico[];
}

/** Un ensayo cerrado de las estadísticas (statRows). */
export interface FilaEstadistica {
  banco: string;
  id: string;
  fecha: string;
  estadoFinal: string;
  motivo: string;
  confirmado: boolean;
  datos: DatosEnsayo;
}

/* ===== Recursos auxiliares (bloque D) ================================== */

/** Un equipo del catálogo (TestBenchResources). */
export interface Recurso {
  name: string;
  groupKey?: string;
  groupName?: string;
  dot?: string;
  code?: string | null;
  org?: string;
  seq?: number;
  [campo: string]: unknown;
}

/** Bloqueo manual de un equipo (calibración, avería…). */
export interface Bloqueo {
  id: string | number;
  resource: string;
  start: string;
  end: string;
  reason?: string;
  note?: string;
  [campo: string]: unknown;
}

export interface Recursos {
  resources: Recurso[];
  blocks?: Bloqueo[];
}

/* ===== Configuración (bloque E) ======================================== */

/** Una fila de la lista compartida de bancos (cfgSnapshot de la 4.9.4). */
export interface BancoCatalogo {
  name: string;
  groupKey: string;
  groupName: string;
  dot: string;
  org: string;
  seq: number;
  /** Categoría de facturación del banco (columna EquipmentType). */
  equipmentType?: string;
  /** Área de la categoría (columna Area; vacío = Thermal Benches). */
  area?: string;
  [campo: string]: unknown;
}

/** Un usuario de TestBenchUsers. */
export interface UsuarioLista {
  timingRoles?: PerfilTiming[];
  title: string;
  email: string;
  role: string;
  responsibleName: string;
}

/** Calendario de festivos de este PC (D6). */
export interface CalendarioPc {
  anio: number;
  saltarFestivos: boolean;
  festivos: { fecha: string; nombre: string }[];
  /** true: calendario común de la base de datos, solo lo cambia un Admin (D46); false: el de este PC (D6). */
  compartido?: boolean;
}

/** Una fila del registro de actividad. */
export interface FilaActividad {
  when?: string;
  name?: string;
  email?: string;
  action?: string;
  detail?: string;
}

/* ===== Test Calendar / Working Hours (D59, solo PostgreSQL) ============= */

/** Una parada del horario de un ensayo. Fechas «aaaa-mm-ddTHH:MM». */
export interface ParadaHorario {
  inicio: string;
  /** Por franjas: fin del tramo [inicio, fin). */
  fin?: string;
  /** Por horas: duración (múltiplo de 0,5). */
  horas?: number;
  /** Por horas: solo cuenta el tiempo dentro de la jornada. */
  afectaTurnos: boolean;
  metodo: 'franjas' | 'horas';
  motivo?: string;
}

/** Horario de un ensayo (null = valor por defecto). */
export interface Horario {
  jornada: 24 | 8 | 16;
  /** Con jornada de 8 h: 'tarde' (14:00–22:00) o null (mañana, 06:00–14:00). Opcional para servidores sin V5. */
  turno?: 'manana' | 'tarde' | null;
  horaInicio: string | null;
  horaFin: string | null;
  /** 'si': trabaja fines de semana y festivos; 'no': no; null: como la opción Weekend del ensayo. */
  finDeSemana: 'si' | 'no' | null;
  setupHoras: number | null;
  paradas: ParadaHorario[];
  nota: string | null;
  /** Solo en las respuestas: horas efectivas («HH:MM»). */
  horaInicioEfectiva?: string;
  horaFinEfectiva?: string;
}

/** Un ensayo en Working Hours (GET /api/v1/horarios?banco=X). */
export interface EnsayoHorario {
  id: string;
  banco: string;
  proyecto: string;
  tipo: string;
  responsable: string;
  /** Descripción del ensayo (columna Comments de la tarea). Opcional por compatibilidad. */
  descripcion?: string | null;
  cerrado: boolean;
  especial: boolean;
  inicio: string | null;
  fin: string | null;
  finDeSemanaEnsayo: boolean;
  porPiezas: boolean;
  paradaDias: number;
  /** Columna antigua setupHours (horas de setup; null si no hay o el banco factura por piezas). */
  setupAntiguo: number | null;
  horario: Horario;
  guardado: boolean;
  /** Concurrencia optimista: 0 = sin registro. */
  version: number;
  modificadoPor: string | null;
  modificadoEn: string | null;
  anulaFinDeSemana: boolean;
}

export interface GestionHorarios {
  editable: boolean;
  puedeEditar: boolean;
  facturacionHorasDesde: string | null;
  jornadas: number[];
}

/** Avisos para la tabla del banco (GET /api/v1/horarios/avisos). */
export interface AvisosHorario {
  editable: boolean;
  bancosPorPiezas: string[];
  ensayos: Record<string, { jornada: number; anulaFinDeSemana: boolean; trabajaFinesDeSemana: boolean }>;
}

/** Vista previa de las horas de un ensayo con un horario. */
export interface VistaPreviaHorario {
  id: string;
  inicio: string;
  fin: string;
  inicioFacturable: string;
  /** Horas de trabajo según el horario, sin descontar setup ni paradas. */
  horasTurno?: number;
  horasFacturables: number;
  horasSetup: number;
  horasParadas: number;
  trabajaFinesDeSemana: boolean;
  anulaFinDeSemana: boolean;
  porPiezas: boolean;
  paradaDias: number;
  paradaDiasIgnorada: boolean;
  avisos: string[];
  /** horas: de trabajo según el horario (sin descontar); horasNetas: las que se facturan. */
  periodos: { mes: string; desde: string; hasta: string; horas: number; horasFacturables?: number; horasNetas: number }[];
  paradas: { inicio: string; fin: string; horas: number }[];
  /** Una fila por día: 48 caracteres, uno por media hora: - fuera, n sin turno, s setup, p parada, w facturable. */
  dias: { fecha: string; franjas: string; laborable: boolean }[];
  rejillaRecortada: boolean;
}
