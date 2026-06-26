package dev.kuml.io.arxml

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotContain

/**
 * Verifies that [ArxmlAdaptiveImporter] applies the same XXE-hardened parser as [ArxmlClassicImporter]
 * (both delegate to [ArxmlSax.secureBuilder]).
 *
 * V3.1.35 — initial implementation.
 */
class ArxmlAdaptiveImporterSecurityTest :
    FunSpec({

        val importer = ArxmlAdaptiveImporter()

        test("adaptive importer blocks DOCTYPE declaration (disallow-doctype-decl)") {
            val xxePayload =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE foo [<!ELEMENT foo ANY>]>
                <AUTOSAR xmlns="http://autosar.org/schema/r4.0">
                  <AR-PACKAGES/>
                </AUTOSAR>
                """.trimIndent()

            shouldThrowAny {
                importer.importFromString(xxePayload)
            }
        }

        test("adaptive importer blocks external general entity (file SYSTEM)") {
            val xxePayload =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE foo [
                  <!ELEMENT foo ANY>
                  <!ENTITY xxe SYSTEM "file:///etc/passwd">
                ]>
                <AUTOSAR xmlns="http://autosar.org/schema/r4.0">
                  <AR-PACKAGES>
                    <AR-PACKAGE>
                      <SHORT-NAME>&xxe;</SHORT-NAME>
                    </AR-PACKAGE>
                  </AR-PACKAGES>
                </AUTOSAR>
                """.trimIndent()

            val thrown =
                shouldThrowAny {
                    importer.importFromString(xxePayload)
                }
            thrown.message?.shouldNotContain("root:") ?: Unit
        }
    })
