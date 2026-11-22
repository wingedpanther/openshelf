package io.github.wingedpanther.openshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbiTest {
    private fun apk(name: String) = Apk(name, abiOf(name), "https://example.com/$name", 1)

    private fun app(vararg apks: Apk) = App(
        id = "a", repo = "o/r", name = "A", pkg = "p", versionCode = 1, versionName = null, minSdk = null,
        room = "", note = "", desc = "", maker = "", stars = 0, licence = null, archived = false, streams = false,
        version = "1", date = "", certSha256 = null, art = AppArt(null, null, null, emptyList()),
        apks = apks.toList(), releases = emptyList(), forkOf = null, parentStars = 0,
    )

    @Test fun abiOfRealWorldNames() {
        assertEquals("arm64-v8a", abiOf("app-arm64-v8a-release.apk"))
        assertEquals("armeabi-v7a", abiOf("app-armeabi-v7a-release.apk"))
        assertEquals("x86_64", abiOf("app-x86_64-release.apk"))
        assertEquals("arm64-v8a", abiOf("App-aarch64.apk"))
        assertEquals("arm64-v8a", abiOf("app-arm64.apk"))
        assertEquals("armeabi-v7a", abiOf("app-armv7.apk"))
        assertEquals("armeabi-v7a", abiOf("app-arm32.apk"))
        assertEquals("x86_64", abiOf("app-x64.apk"))
        assertEquals("x86_64", abiOf("app-amd64.apk"))
        assertEquals("x86_64", abiOf("app-x86-64.apk"))
        assertEquals("x86", abiOf("app-x86-release.apk"))
        assertEquals("universal", abiOf("app-universal-release.apk"))
        assertEquals("any", abiOf("app-release.apk"))
    }

    @Test fun abiOfIsCaseInsensitive() {
        assertEquals("arm64-v8a", abiOf("App-ARM64-V8A.apk"))
        assertEquals("universal", abiOf("APP-Universal.apk"))
    }

    @Test fun pickApkPrefersTheDevicesOwnAbi() {
        val a = app(apk("app-arm64-v8a.apk"), apk("app-armeabi-v7a.apk"), apk("app-universal.apk"))
        assertEquals("app-armeabi-v7a.apk", pickApk(a, listOf("armeabi-v7a")).name)
        assertEquals("app-arm64-v8a.apk", pickApk(a, listOf("arm64-v8a", "armeabi-v7a", "armeabi")).name)
    }

    @Test fun pickApkFollowsTheDevicesAbiOrder() {
        val a = app(apk("app-arm64-v8a.apk"), apk("app-armeabi-v7a.apk"))
        assertEquals("app-armeabi-v7a.apk", pickApk(a, listOf("armeabi-v7a", "arm64-v8a")).name)
    }

    @Test fun pickApkFallsBackToUniversalThenAnyThenFirst() {
        val universal = app(apk("app-arm64-v8a.apk"), apk("app-release.apk"), apk("app-universal.apk"))
        assertEquals("app-universal.apk", pickApk(universal, listOf("armeabi-v7a")).name)
        val any = app(apk("app-arm64-v8a.apk"), apk("app-release.apk"))
        assertEquals("app-release.apk", pickApk(any, listOf("armeabi-v7a")).name)
        val first = app(apk("app-arm64-v8a.apk"), apk("app-x86_64.apk"))
        assertEquals("app-arm64-v8a.apk", pickApk(first, listOf("armeabi-v7a")).name)
    }

    @Test fun pickApkFromListPrefersTheDevicesAbi() {
        val list = listOf(apk("app-arm64-v8a.apk"), apk("app-armeabi-v7a.apk"), apk("app-universal.apk"))
        assertEquals("app-armeabi-v7a.apk", ManualLinks.pickApkFromList(list, listOf("armeabi-v7a"))?.name)
    }

    @Test fun pickApkFromListFallsBackToUniversalThenAny() {
        val universal = listOf(apk("app-arm64-v8a.apk"), apk("app-release.apk"), apk("app-universal.apk"))
        assertEquals("app-universal.apk", ManualLinks.pickApkFromList(universal, listOf("armeabi-v7a"))?.name)
        val any = listOf(apk("app-arm64-v8a.apk"), apk("app-release.apk"))
        assertEquals("app-release.apk", ManualLinks.pickApkFromList(any, listOf("armeabi-v7a"))?.name)
    }

    @Test fun pickApkFromListHasNoFirstFallback() {
        val list = listOf(apk("app-arm64-v8a.apk"), apk("app-x86_64.apk"))
        assertNull(ManualLinks.pickApkFromList(list, listOf("armeabi-v7a")))
        assertNull(ManualLinks.pickApkFromList(emptyList(), listOf("armeabi-v7a")))
    }
}
