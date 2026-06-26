package dev.kuml.io.arxml

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotContain

/**
 * Verifies that [ArxmlClassicImporter] applies the same XXE-hardened parser as [ArxmlReader]
 * (both delegate to [ArxmlSax.secureBuilder]).
 *
 * V3.1.34 — initial implementation.
 */
class ArxmlClassicImporterSecurityTest :
    FunSpec({

        val importer = ArxmlClassicImporter()

        test("importer blocks DOCTYPE declaration (disallow-doctype-decl)") {
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

        test("importer blocks external general entity (file SYSTEM)") {
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

        test("importer blocks billion-laughs entity expansion") {
            val billionLaughs =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE lolz [
                  <!ENTITY lol "lol">
                  <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
                  <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
                ]>
                <AUTOSAR xmlns="http://autosar.org/schema/r4.0">
                  <AR-PACKAGES>
                    <AR-PACKAGE><SHORT-NAME>&lol3;</SHORT-NAME></AR-PACKAGE>
                  </AR-PACKAGES>
                </AUTOSAR>
                """.trimIndent()

            shouldThrowAny {
                importer.importFromString(billionLaughs)
            }
        }
    })
