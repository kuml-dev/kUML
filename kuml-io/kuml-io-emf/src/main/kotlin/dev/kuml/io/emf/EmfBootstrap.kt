package dev.kuml.io.emf

import org.eclipse.emf.ecore.EPackage
import org.eclipse.uml2.uml.UMLPackage

/**
 * Einmalige EMF/UML2-Initialisierung.
 *
 * Muss vor jeder EMF-Verwendung aufgerufen werden (EcoreFactory.eINSTANCE
 * wirft NullPointer wenn das EPackage noch nicht registriert ist).
 *
 * Thread-safe: Kotlin object + @Volatile flag.
 *
 * V3.0.15: UMLResourcesUtil ist nicht im Maven-verteilten UML2-Artefakt
 * (org.eclipse.uml2.uml.resources ist nur ein Eclipse-P2-Plugin, nicht auf
 * Maven Central / Eclipse Nexus verfügbar). Stattdessen wird UMLPackage
 * direkt in der globalen EPackage.Registry registriert — das reicht für
 * den XMI-Import/-Export ohne Primitive-Types-Profile (MVP).
 */
public object EmfBootstrap {
    @Volatile
    private var initialized = false

    public fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // Explizit UMLPackage in der globalen EPackage.Registry registrieren
            EPackage.Registry.INSTANCE.getOrPut(UMLPackage.eNS_URI) { UMLPackage.eINSTANCE }
            initialized = true
        }
    }

    /** Für Tests: Reset damit init() erneut ausgeführt werden kann. */
    internal fun resetForTest() {
        initialized = false
    }
}
