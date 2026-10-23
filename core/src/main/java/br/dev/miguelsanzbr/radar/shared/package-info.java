/**
 * Shared kernel: value objects and the event envelope every context uses. Open so any module may
 * depend on its types; it depends on no other module.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package br.dev.miguelsanzbr.radar.shared;

import org.springframework.modulith.ApplicationModule;
