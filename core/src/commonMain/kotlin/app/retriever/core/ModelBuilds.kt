/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/data/ModelAllowlist.kt (Apache-2.0). Modified for Retriever: socToModelFiles becomes a commit-pinned EmbeddingGemma 2 NPU manifest with sizes and sha256, plus variant selection and fallback rules.

package app.retriever.core

object ModelBuilds {
    /** litert-community NPU builds per SoC, fetched from the HF API 2026-10-17. Intel PTL and QCS8275 are not phone SoCs. */
    val npu: List<ModelBuild> = listOf(
        npuBuild(ModelVariant.M740, "Google_Tensor_G5", 657_656_500, "5b53d76c2c7c66d2dc6230ff158ea0033ac5939ac7cfcf827c3a22cdfc742a5e"),
        npuBuild(ModelVariant.M740, "Google_Tensor_G6", 615_713_460, "ed0e4f413e705946eda544078d8bf5acf10fe5aee2946451c81919a1f59665e7"),
        npuBuild(ModelVariant.M740, "MediaTek_MT6991", 895_008_768, "99e6a35b824b4f7ad4f7c7f20c59ba380aa90e0b4442aadfee3674230c6c7cca"),
        npuBuild(ModelVariant.M740, "MediaTek_MT6993", 864_681_984, "ed2a2554bfb6d7abcafd0e9e36ee2f8d7de82888e7ed0fc52dd144e65b171742"),
        npuBuild(ModelVariant.M740, "Qualcomm_SM8550", 562_905_088, "802686a865fd7350f2ce4b6fa17ce02a411f56b5b508ec2bd8cb7310b2db82e6"),
        npuBuild(ModelVariant.M740, "Qualcomm_SM8650", 562_659_328, "3b8a18c6d043b8132c4eda45611296e1ae455ada82cda8b591ec0ca141e041c5"),
        npuBuild(ModelVariant.M740, "Qualcomm_SM8750", 543_522_816, "ac1973c0195dc6e28a621651f2f5e04d35fdc7a7b11cdbbe94f29f5dccb57996"),
        npuBuild(ModelVariant.M740, "Qualcomm_SM8850", 559_448_064, "19f6e8db6aa74b116d1c558c862ee131f60ce093576d3c8dd1898f656ca8080b"),
        npuBuild(ModelVariant.M440, "Google_Tensor_G5", 559_418_036, "96cdbae7b3dd6fa0f829fef2d0dd5bafc0179890d9bafbfe972db852e818d758"),
        npuBuild(ModelVariant.M440, "Google_Tensor_G6", 513_166_004, "40e98aeed9ef08abfd91d5d309f64f4fa06195c513931e917bcf5ea41a910668"),
        npuBuild(ModelVariant.M440, "MediaTek_MT6991", 798_097_408, "53cb4b8b3d8ca3e022ceec1dd809ddd26c281721c4652e1fadc82d2433cbc5de"),
        npuBuild(ModelVariant.M440, "MediaTek_MT6993", 767_770_624, "1cfc52d73266fe0e099e853b816150c22deb32fea421cabf9647dc8d8112ede9"),
        npuBuild(ModelVariant.M440, "Qualcomm_SM8550", 465_993_728, "4427eecca4289555e72b297308f4c9cf97110a657de25dd2fe7946ac824f6214"),
        npuBuild(ModelVariant.M440, "Qualcomm_SM8650", 465_747_968, "ec7d2ee513830337343567409f32fea4a3e4f8f7b94facd0a20f3d3523cd7480"),
        npuBuild(ModelVariant.M440, "Qualcomm_SM8750", 446_611_456, "c5c05cb59833541a2d0d9374c7b50d2c3a406a4a84c85762775e5f9c6d96cd0c"),
        npuBuild(ModelVariant.M440, "Qualcomm_SM8850", 462_536_704, "661369235aa9fc23cf04a213d4e445c4634073832279f5a261ed22650a8ebc5b"),
    )

    /** A native crash (SIGSEGV) in the NPU delegate throws nothing; opens that never reached a first embed count instead. */
    const val MAX_UNFINISHED_OPENS = 2

    fun variantFor(probe: DeviceProbe): ModelVariant = when {
        probe.platform == Platform.IOS -> ModelVariant.M440
        probe.ramGb >= 7.0 -> ModelVariant.M740
        else -> ModelVariant.M440
    }

    /** Build key = socKey of the soc after its vendor ('tensorg5', 'sm8650'); a prefix match tolerates 'SM8650-AC'. */
    fun socKey(s: String): String = s.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    fun buildFor(variant: ModelVariant, probe: DeviceProbe, failed: Set<String>): ModelBuild {
        if (probe.platform != Platform.ANDROID || probe.sdkInt < 31 || !probe.npuDispatch) return variant.generic
        val device = socKey(probe.socModel)
        return npu.firstOrNull {
            it.variant == variant && it.fileName !in failed && device.startsWith(socKey(it.soc!!.substringAfter('_')))
        } ?: variant.generic
    }

    fun fallback(build: ModelBuild): ModelBuild = build.variant.generic

    fun shouldFallBack(unfinishedOpens: Int): Boolean = unfinishedOpens >= MAX_UNFINISHED_OPENS

    private fun npuBuild(variant: ModelVariant, soc: String, size: Long, sha256: String) =
        ModelBuild(variant, variant.fileName.removeSuffix(".litertlm") + "_$soc.litertlm", size, sha256, soc)
}
