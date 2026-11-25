
import test from 'node:test';
import assert from 'node:assert/strict';
import { enlaceTiming, grupoOrden, leerRutaTiming, nuevoOrdenDiario, ordenarCola, ordenarTareas, sugerenciasTiming, vistaInicialTiming, vistasTiming } from '../target-test/bloques/timing/modelo.js';

test('abre enlaces de Timing sin capturar la navegación histórica del Planner', () => {
  assert.equal(leerRutaTiming('#/timing'), null);
  assert.equal(leerRutaTiming('#/shaker%2F1'), null);
  assert.deepEqual(leerRutaTiming(enlaceTiming()), {});
  assert.deepEqual(leerRutaTiming(enlaceTiming('planificacion', 42)), { vista: 'planificacion', id: 42 });
  assert.deepEqual(leerRutaTiming('#/timing/planificacion/tarea/no-valida'), { invalida: true });
  assert.deepEqual(leerRutaTiming('#/timing/configuracion/tarea/42'), { invalida: true });
});

test('oculta Timing sin un perfil y elige la vista principal por perfil', () => {
  assert.deepEqual(vistasTiming({ modoServidor: true, esAdmin: true, timingRoles: [] }), []);
  assert.deepEqual(vistasTiming({ modoServidor: false, timingRoles: ['tecnico'] }), []);
  const tecnico = vistasTiming({ modoServidor: true, rol: 'readonly', timingRoles: ['solicitante', 'tecnico'] });
  assert.deepEqual(tecnico, ['solicitudes', 'cola', 'historico']);
  assert.equal(vistaInicialTiming(tecnico), 'cola');
  assert.equal(vistaInicialTiming([...tecnico, 'planificacion']), 'planificacion');
});

test('ordena la cola como Timing R&T: orden del día, cola (9) y genéricas (99), después prioridad', () => {
  const tareas = [
    { id: 1, ordenDiario: 9, prioridad: 2 },
    { id: 2, ordenDiario: 1, prioridad: 50 },
    { id: 3, ordenDiario: 99, prioridad: 999 },
    { id: 4, ordenDiario: 0, prioridad: 100 },
    { id: 5, prioridad: 1 },
    { id: 6, ordenDiario: -0.5 },
  ];
  assert.deepEqual(ordenarCola(tareas).map((t) => t.id), [6, 4, 2, 5, 1, 3]);
  assert.deepEqual(tareas.map(grupoOrden), ['cola', 'dia', 'final', 'dia', 'cola', 'dia']);
});

test('el panel del coordinador numera el día y solo envía lo que cambia', () => {
  const a = { id: 1, ordenDiario: 1 }, b = { id: 2, ordenDiario: 9 }, c = { id: 3, ordenDiario: 0 }, d = { id: 4, ordenDiario: 99 }, e = { id: 5, ordenDiario: 3 };
  const cambios = nuevoOrdenDiario({ dia: [a, b], cola: [c, { id: 6, ordenDiario: 9.5 }], final: [d, e] });
  assert.deepEqual(cambios.map((x) => [x.tarea.id, x.ordenDiario]), [[2, 2], [3, 9], [5, 99]]);
  // Sin mover nada no hay cambios, aunque el día empiece en 0.
  assert.deepEqual(nuevoOrdenDiario({ dia: [c, a], cola: [b], final: [d] }), []);
});

test('ordena los números de proyecto y deja al final las tareas sin número', () => {
  const tareas = [{ id: 1, cuenta: '010' }, { id: 2, cuenta: '002' }, { id: 3 }];
  assert.deepEqual(ordenarTareas(tareas, { columna: 'cuenta', descendente: false }).map((t) => t.id), [2, 1, 3]);
  assert.deepEqual(ordenarTareas(tareas, { columna: 'cuenta', descendente: true }).map((t) => t.id), [1, 2, 3]);
});

test('sugiere datos importados aunque no estén vinculados al catálogo', () => {
  const tareas = [{ cuenta: '00123', proyecto: 'Proyecto histórico', responsableOrigen: 'Ana', tecnicoOrigen: 'Berta', banco: 'Banco 3' }];
  const opciones = { proyectos: [{ nombre: 'Proyecto actual', cuenta: '00456' }], personas: [{ nombre: 'Ana', responsable: true }], equipos: [] };
  assert.deepEqual(sugerenciasTiming(tareas, opciones), {
    proyecto: ['Proyecto actual', 'Proyecto histórico'], cuenta: ['00123', '00456'], responsable: ['Ana'], tecnico: ['Berta'], banco: ['Banco 3'],
  });
});
