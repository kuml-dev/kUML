package dev.kuml.renderer.theme.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KumlColorTest :
    FunSpec({

        test("toHex formats black as #000000") {
            KumlColor(0x000000).toHex() shouldBe "#000000"
        }

        test("toHex formats mixed color correctly") {
            KumlColor(0xFFAA33).toHex() shouldBe "#FFAA33"
        }

        test("toHex masks high bits for negative int values") {
            // Negative Int (all bits set in sign position) must be masked to 6 hex digits
            KumlColor(-1).toHex() shouldBe "#FFFFFF"
        }

        test("Black companion returns #000000") {
            KumlColor.Black.toHex() shouldBe "#000000"
        }

        test("White companion returns #FFFFFF") {
            KumlColor.White.toHex() shouldBe "#FFFFFF"
        }
    })
