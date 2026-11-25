import { useEffect, type RefObject } from 'react';

/*
 * Hotfix: en las pantallas de configuración (Test Benches, Projects, Areas…)
 * cada cambio se guarda en segundo plano y, al terminar, se recarga la vista.
 * Si en ese momento el contenido se vuelve a pintar, el navegador puede dejar
 * la página arriba del todo. Este gancho recuerda dónde estaba el usuario y, si
 * la posición salta sin que él haya hecho nada (rueda, teclado, ratón o dedo),
 * la devuelve a su sitio, reintentando unos instantes mientras el contenido
 * termina de pintarse.
 */
export function useConservarScroll(contenedor: RefObject<HTMLElement | null>, activo: boolean) {
  useEffect(() => {
    const el = contenedor.current;
    if (!el || !activo) return;
    let posicion = el.scrollTop;
    let ultimaAccion = 0;
    let restaurando = 0;
    let pulsado = false; // botón del ratón pulsado (p. ej. arrastrando la barra de desplazamiento)
    const accion = () => { ultimaAccion = Date.now(); };
    const pulsar = () => { pulsado = true; accion(); };
    const soltar = () => { pulsado = false; accion(); };
    const restaurar = (destino: number) => {
      const hasta = Date.now() + 1500;
      const paso = () => {
        if (pulsado || Date.now() - ultimaAccion < 400 || Date.now() > hasta) { restaurando = 0; return; }
        if (Math.abs(el.scrollTop - destino) > 2) el.scrollTop = destino;
        restaurando = Math.abs(el.scrollTop - destino) > 2 ? requestAnimationFrame(paso) : 0;
      };
      cancelAnimationFrame(restaurando);
      restaurando = requestAnimationFrame(paso);
    };
    const alDesplazar = () => {
      const ahora = el.scrollTop;
      const delUsuario = pulsado || Date.now() - ultimaAccion < 800;
      // Salto brusco hacia arriba que no ha pedido nadie: se vuelve a donde estaba.
      if (!delUsuario && posicion > 120 && ahora < posicion - 120) {
        restaurar(posicion);
        return;
      }
      if (!restaurando) posicion = ahora;
    };
    const eventos = ['wheel', 'keydown', 'pointerdown', 'touchstart'] as const;
    eventos.forEach((e) => el.addEventListener(e, accion, { passive: true }));
    // Barra de desplazamiento y teclas fuera del contenedor (p. ej. Inicio).
    window.addEventListener('keydown', accion, { passive: true });
    window.addEventListener('pointerdown', pulsar, { capture: true, passive: true });
    window.addEventListener('mousedown', pulsar, { capture: true, passive: true });
    window.addEventListener('pointerup', soltar, { capture: true, passive: true });
    window.addEventListener('mouseup', soltar, { capture: true, passive: true });
    el.addEventListener('scroll', alDesplazar, { passive: true });
    // Si el contenido se repinta y encoge, puede no haber evento de scroll hasta que vuelve a crecer.
    const observador = new ResizeObserver(() => {
      if (!pulsado && Date.now() - ultimaAccion > 800 && posicion > 120 && el.scrollTop < posicion - 120) restaurar(posicion);
    });
    Array.from(el.children).forEach((h) => observador.observe(h));
    const mutaciones = new MutationObserver(() => {
      observador.disconnect();
      Array.from(el.children).forEach((h) => observador.observe(h));
    });
    mutaciones.observe(el, { childList: true });
    return () => {
      eventos.forEach((e) => el.removeEventListener(e, accion));
      window.removeEventListener('keydown', accion);
      window.removeEventListener('pointerdown', pulsar, { capture: true });
      window.removeEventListener('mousedown', pulsar, { capture: true });
      window.removeEventListener('pointerup', soltar, { capture: true });
      window.removeEventListener('mouseup', soltar, { capture: true });
      el.removeEventListener('scroll', alDesplazar);
      observador.disconnect();
      mutaciones.disconnect();
      cancelAnimationFrame(restaurando);
    };
  }, [contenedor, activo]);
}
