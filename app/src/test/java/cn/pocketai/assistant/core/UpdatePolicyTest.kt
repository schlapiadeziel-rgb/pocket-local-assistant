package cn.pocketai.assistant.core

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val base = "https://github.com/${UpdatePolicy.REPOSITORY}/releases/download/v0.2.0/"
    private val file = "pocket-local-assistant-0.2.0-arm64.apk"
    @Test fun validPinnedRepoAsset() { assertTrue(UpdatePolicy.validAssetUrl(base + file, file)) }
    @Test fun cleartextRejected() { assertFalse(UpdatePolicy.validAssetUrl((base + file).replace("https:", "http:"), file)) }
    @Test fun foreignHostRejected() { assertFalse(UpdatePolicy.validAssetUrl((base + file).replace("github.com", "github.com.evil.example"), file)) }
    @Test fun foreignRepoRejected() { assertFalse(UpdatePolicy.validAssetUrl((base + file).replace("pocket-local-assistant/", "other/"), file)) }
    @Test fun userInfoRejected() { assertFalse(UpdatePolicy.validAssetUrl((base + file).replace("github.com", "x@github.com"), file)) }
    @Test fun queryRejected() { assertFalse(UpdatePolicy.validAssetUrl(base + file + "?url=elsewhere", file)) }
    @Test fun extraPathRejected() { assertFalse(UpdatePolicy.validAssetUrl(base + "../" + file, file)) }
    @Test fun encodedPathRejected() { assertFalse(UpdatePolicy.validAssetUrl(base + "%2E%2E/" + file, file)) }
    @Test fun wrongFilenameRejected() { assertFalse(UpdatePolicy.validAssetUrl(base + "other.apk", file)) }
    @Test fun validDigest() { assertTrue(UpdatePolicy.validDigest("a".repeat(64))) }
    @Test fun shortDigestRejected() { assertFalse(UpdatePolicy.validDigest("a".repeat(63))) }
    @Test fun malformedDigestRejected() { assertFalse(UpdatePolicy.validDigest("g".repeat(64))) }
}
