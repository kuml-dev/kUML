package dev.kuml.jetbrains.markdown

import dev.kuml.jetbrains.KumlPreviewRenderer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KumlMarkdownPreviewCacheTest :
    FunSpec({
        beforeEach {
            KumlMarkdownPreviewCache.clear()
        }

        test("computeKey generates deterministic SHA-256 hashes") {
            val key1 = KumlMarkdownPreviewCache.computeKey("classDiagram { }", "plain", "diag1")
            val key2 = KumlMarkdownPreviewCache.computeKey("classDiagram { }", "plain", "diag1")
            key1 shouldBe key2
            key1.length shouldBe 64
        }

        test("computeKey produces different hashes when content, theme, or name change") {
            val base = KumlMarkdownPreviewCache.computeKey("classDiagram { }", "plain", "diag1")
            val diffContent = KumlMarkdownPreviewCache.computeKey("stateDiagram { }", "plain", "diag1")
            val diffTheme = KumlMarkdownPreviewCache.computeKey("classDiagram { }", "elegant", "diag1")
            val diffName = KumlMarkdownPreviewCache.computeKey("classDiagram { }", "plain", "diag2")

            base shouldNotBe diffContent
            base shouldNotBe diffTheme
            base shouldNotBe diffName
        }

        test("get and put correctly store and retrieve outcomes") {
            val key = KumlMarkdownPreviewCache.computeKey("test source", "plain", "test")
            KumlMarkdownPreviewCache.get(key) shouldBe null

            val outcome = KumlPreviewRenderer.Outcome.Svg("<svg></svg>")
            KumlMarkdownPreviewCache.put(key, outcome)

            KumlMarkdownPreviewCache.get(key) shouldBe outcome
            KumlMarkdownPreviewCache.size() shouldBe 1
        }

        test("clear resets the cache") {
            KumlMarkdownPreviewCache.put("key1", KumlPreviewRenderer.Outcome.Svg("<svg>1</svg>"))
            KumlMarkdownPreviewCache.put("key2", KumlPreviewRenderer.Outcome.Svg("<svg>2</svg>"))
            KumlMarkdownPreviewCache.size() shouldBe 2

            KumlMarkdownPreviewCache.clear()
            KumlMarkdownPreviewCache.size() shouldBe 0
            KumlMarkdownPreviewCache.get("key1") shouldBe null
        }

        test("LRU eviction limits maximum entries to MAX_ENTRIES") {
            for (i in 1..60) {
                val key = "key-$i"
                KumlMarkdownPreviewCache.put(key, KumlPreviewRenderer.Outcome.Svg("<svg>$i</svg>"))
            }

            KumlMarkdownPreviewCache.size() shouldBe KumlMarkdownPreviewCache.MAX_ENTRIES
            // Earliest inserted items should have been evicted
            KumlMarkdownPreviewCache.get("key-1") shouldBe null
            KumlMarkdownPreviewCache.get("key-10") shouldBe null
            // Latest inserted items must still be present
            KumlMarkdownPreviewCache.get("key-60") shouldNotBe null
        }

        test("blank scriptText returns Outcome.Empty") {
            val emptyOutcome = KumlMarkdownPreviewCache.getOrRender("   \n\t  ", "plain", "empty-diagram")
            emptyOutcome shouldBe KumlPreviewRenderer.Outcome.Empty
        }

        test("concurrent cache reads and writes are thread safe") {
            val pool = Executors.newFixedThreadPool(8)
            for (i in 0 until 100) {
                pool.submit {
                    val key = "thread-key-${i % 20}"
                    KumlMarkdownPreviewCache.put(key, KumlPreviewRenderer.Outcome.Svg("<svg>$i</svg>"))
                    KumlMarkdownPreviewCache.get(key)
                }
            }
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS) shouldBe true
            (KumlMarkdownPreviewCache.size() <= KumlMarkdownPreviewCache.MAX_ENTRIES) shouldBe true
        }
    })
