package studio.npu.x200t.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.npu.sd.ModelType

class ModelCatalogTest {
    @Test
    fun compactModelsHaveCompleteDownloadsAndClassicDefaults() {
        val compact = ModelCatalog.models.filter { it.compact }
        assertEquals(setOf("segmind-tiny-sd", "bk-sdm-small"), compact.map { it.id }.toSet())
        val required = setOf(
            "tokenizer/vocab.json", "tokenizer/merges.txt", "text_encoder/model.onnx",
            "unet/model.onnx", "vae_decoder/model.onnx", "LICENSE.md",
        )
        for (model in compact) {
            assertEquals(required, model.files.map { it.path }.toSet())
            assertTrue(model.files.all { it.size > 0 })
            assertTrue(model.totalBytes < 1_500_000_000L)
            assertEquals(ModelType.SD15, model.type)
            assertEquals(20, model.defaultSteps)
            assertEquals(7.5f, model.defaultGuidance, 0f)
            assertTrue(model.revision.matches(Regex("[a-f0-9]{40}")))
            assertTrue(model.files.all { model.url(it).contains("/resolve/${model.revision}/") })
            assertFalse(model.files.any { it.path == "unet/model.onnx_data" })
        }
        assertEquals(1_093_337_150L, ModelCatalog.byId("segmind-tiny-sd")!!.totalBytes)
        assertEquals(1_411_285_446L, ModelCatalog.byId("bk-sdm-small")!!.totalBytes)
    }

    @Test
    fun existingCatalogModelsAndDownloadLocationsArePreserved() {
        val original = ModelCatalog.models.filterNot { it.compact }
        assertEquals(
            listOf("lcm-dreamshaper-v7", "instaflow-0.9b", "epicrealism", "deliberate-v3"),
            original.map { it.id },
        )
        for (model in original) {
            assertEquals("main", model.revision)
            assertTrue(model.files.any { it.path == "unet/model.onnx_data" })
            assertTrue(model.files.all {
                model.url(it) == "https://huggingface.co/${model.repo}/resolve/main/${it.path}"
            })
        }
        assertEquals(ModelCatalog.models.size, ModelCatalog.models.map { it.id }.toSet().size)
    }
}
