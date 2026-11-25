import test from 'node:test';
import assert from 'node:assert/strict';
import { etiquetaProyecto, nombreConNumero, numeroLargo, separarNuevo, tieneFormaNumeroNombre } from '../target-test/bloques/configuracion/nombresProyecto.js';

test('números de proyecto de la serie 8 en formato largo, como el backend', () => {
  assert.equal(numeroLargo('80834'), '80000834');
  assert.equal(numeroLargo('81886'), '80001886');
  assert.equal(numeroLargo('801786'), '80001786');
  assert.equal(numeroLargo('8000475'), '80000475');
  assert.equal(numeroLargo('800001786'), '80001786');
  assert.equal(numeroLargo('80000834'), '80000834');
  assert.equal(numeroLargo('10766'), '10766');
  assert.equal(numeroLargo('00877'), '00877');
  assert.equal(numeroLargo('463801054'), '463801054');
});

test('etiqueta «número - nombre» y nombres que aún llevan el número', () => {
  assert.equal(etiquetaProyecto('Rimac - eCooler', '80001755'), '80001755 - Rimac - eCooler');
  assert.equal(etiquetaProyecto('Lab', ''), 'Lab');
  assert.equal(etiquetaProyecto('80002613 VW EGR', '80002613'), '80002613 VW EGR');
  assert.equal(etiquetaProyecto('00042 - Prueba', '00043'), '00042 - Prueba');
  assert.ok(nombreConNumero('80001755 -Rimac'));
  assert.ok(!nombreConNumero('Rimac 2026'));
  assert.ok(tieneFormaNumeroNombre('81000 - Otro'));
  assert.ok(!tieneFormaNumeroNombre('Otro'));
  assert.deepEqual(separarNuevo('80834 - Stellantis Small'), { numero: '80000834', nombre: 'Stellantis Small' });
  assert.deepEqual(separarNuevo('80001755 - 80001755 Rimac'), { numero: '', nombre: '80001755 - 80001755 Rimac' });
  assert.deepEqual(separarNuevo('Mejoras laboratorio'), { numero: '', nombre: 'Mejoras laboratorio' });
});
