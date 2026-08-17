package dev.kuml.jetbrains.preview

import dev.kuml.jetbrains.KumlPreviewRenderer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KumlDocPreviewCacheTest :
    FunSpec({
        beforeEach {
            KumlDocPreviewCache.clear()
        }

        test("computeKey generates deterministic SHA-256 hashes") {
            val key1 = KumlDocPreviewCache.computeKey("classDiagram { }", "plain", "diag1")
            val key2 = KumlDocPreviewCache.computeKey("classDiagram { }", "plain", "diag1")
            key1 shouldBe key2
            key1.length shouldBe 64
        }

        test("computeKey produces different hashes when content, theme, or name change") {
            val base = KumlDocPreviewCache.computeKey("classDiagram { }", "plain", "diag1")
            val diffContent = KumlDocPreviewCache.computeKey("stateDiagram { }", "plain", "diag1")
            val diffTheme = KumlDocPreviewCache.computeKey("classDiagram { }", "elegant", "diag1")
            val diffName = KumlDocPreviewCache.computeKey("classDiagram { }", "plain", "diag2")

            base shouldNotBe diffContent
            base shouldNotBe diffTheme
            base shouldNotBe diffName
        }

        test("get and put correctly store and retrieve outcomes") {
            val key = KumlDocPreviewCache.computeKey("test source", "plain", "test")
            KumlDocPreviewCache.get(key) shouldBe null

            val outcome = KumlPreviewRenderer.Outcome.Svg("<svg></svg>")
            KumlDocPreviewCache.put(key, outcome)

            KumlDocPreviewCache.get(key) shouldBe outcome
            KumlDocPreviewCache.size() shouldBe 1
        }

        test("clear resets the cache") {
            KumlDocPreviewCache.put("key1", KumlPreviewRenderer.Outcome.Svg("<svg>1</svg>"))
            KumlDocPreviewCache.put("key2", KumlPreviewRenderer.Outcome.Svg("<svg>2</svg>"))
            KumlDocPreviewCache.size() shouldBe 2

            KumlDocPreviewCache.clear()
            KumlDocPreviewCache.size() shouldBe 0
            KumlDocPreviewCache.get("key1") shouldBe null
        }

        test("LRU eviction limits maximum entries to MAX_ENTRIES") {
            for (i in 1..60) {
                val key = "key-$i"
                KumlDocPreviewCache.put(key, KumlPreviewRenderer.Outcome.Svg("<svg>$i</svg>"))
            }

            KumlDocPreviewCache.size() shouldBe KumlDocPreviewCache.MAX_ENTRIES
            // Earliest inserted items should have been evicted
            KumlDocPreviewCache.get("key-1") shouldBe null
            KumlDocPreviewCache.get("key-10") shouldBe null
            // Latest inserted items must still be present
            KumlDocPreviewCache.get("key-60") shouldNotBe null
        }

        test("blank scriptText returns Outcome.Empty") {
            val emptyOutcome = KumlDocPreviewCache.getOrRender("   \n\t  ", "plain", "empty-diagram")
            emptyOutcome shouldBe KumlPreviewRenderer.Outcome.Empty
        }

        test("concurrent cache reads and writes are thread safe") {
            val pool = Executors.newFixedThreadPool(8)
            for (i in 0 until 100) {
                pool.submit {
                    val key = "thread-key-${i % 20}"
                    KumlDocPreviewCache.put(key, KumlPreviewRenderer.Outcome.Svg("<svg>$i</svg>"))
                    KumlDocPreviewCache.get(key)
                }
            }
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS) shouldBe true
            (KumlDocPreviewCache.size() <= KumlDocPreviewCache.MAX_ENTRIES) shouldBe true
        }
    })
