
import test from 'node:test';
import assert from 'node:assert/strict';
import { enlaceTiming, leerRutaTiming, ordenarCola, ordenarTareas, sugerenciasTiming, vistaInicialTiming, vistasTiming } from '../target-test/bloques/timing/modelo.js';

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

test('la cola personal prioriza el orden del día, luego los pendientes sin fecha y otras fechas', () => {
  const dia = '2026-11-23';
  const tareas = [
    { id: 1, diaTrabajo: dia, ordenDiario: 5, prioridad: -1 },
    { id: 2, diaTrabajo: dia, ordenDiario: 1, prioridad: 2 },
    { id: 3, prioridad: -2 },
    { id: 4, diaTrabajo: '2026-11-24', ordenDiario: -10 },
    { id: 5, diaTrabajo: dia, ordenDiario: 1, prioridad: 2 },
  ];
  assert.deepEqual(ordenarCola(tareas, dia).map((t) => t.id), [2, 5, 1, 3, 4]);
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
