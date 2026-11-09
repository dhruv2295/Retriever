// People's face model (SFace, 17 MB), delivered by Play only when the person turns People on, which is off by default:
// it was 30% of every phone's download.
plugins {
    alias(libs.plugins.android.asset.pack)
}

assetPack {
    packName.set("facepack")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
