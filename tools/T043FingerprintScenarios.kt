package com.example.airemote

/** Pure Kotlin fingerprint contract checks; runs in CI without Android or network. */
object T043FingerprintScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun assertEq(actual: String, expected: String, title: String) {
            check(actual == expected) { "$title: expected $expected but got $actual" }
            passed++
        }
        fun assertTrue(condition: Boolean, title: String) {
            check(condition) { title }
            passed++
        }
        fun hash(text: String) = T043MessageFingerprint.sha256(
            T043MessageFingerprint.canonicalJson(text.toByteArray(Charsets.UTF_8)))
        assertEq(hash("""{"b":2,"a":1}"""),
            "43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777","V01")
        assertEq(hash("""{"a":1,"b":2}"""),
            "43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777","V01 replay")
        assertEq(hash("""{"n":9007199254740993}"""),
            "4ac8309cc76123ef6c5325ef925fc873e9b5856ec4f844ef1462f9303960378a","V02 big integer")
        assertEq(hash("""{"n":100}"""),
            "477bbdd93b24d7ebe5b110b7bd76f53e82c2831cd361d557779cbf3f1da9c522","V03")
        assertEq(hash("""{"n":1.00e2}"""),
            "477bbdd93b24d7ebe5b110b7bd76f53e82c2831cd361d557779cbf3f1da9c522","V03 decimal")
        assertEq(hash("""{"n":1}"""),
            "2bfd14f43d17fc7cea24e0917a8879b4b2f880b8baeec1b9d90fbaad655e71bd","V04")
        assertEq(hash("""{"n":1.00e+0}"""),
            "2bfd14f43d17fc7cea24e0917a8879b4b2f880b8baeec1b9d90fbaad655e71bd","V04 decimal")
        assertEq(hash("""{"é":"😀","x":"\u0061"}"""),
            "a86028a753c46fc19f0934078aaa243b814d37aebe9ed1942b30ddbaa21f4e3e","V05 Unicode")
        assertEq(hash("""{"\u00e9":"\ud83d\ude00","x":"a"}"""),
            "a86028a753c46fc19f0934078aaa243b814d37aebe9ed1942b30ddbaa21f4e3e","V05 escaped")
        fun raw(input: String, algorithm: String = "json-semantic.v1") =
            T043MessageFingerprint.digest(input.toByteArray(Charsets.UTF_8),"JSON_UTF8",algorithm)
        val duplicates = raw("""{"a":1,"\u0061":2}""")
        val malformed = raw("""{"a":""")
        val unknown = raw("""{"a":1}""","json-semantic.v99")
        assertEq(duplicates.rawSha256,
            "c95ba945b310a34906bbfc28ce1346b87741d5a2b3066d1d3ecfa0acbd86392c","V06 raw")
        assertEq(malformed.rawSha256,
            "ffb38b22ee3e0ca90325ebce953a9846990f292faf44c50498771602e31cb61f","V07 raw")
        assertEq(unknown.rawSha256,
            "015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862","V08 raw")
        assertTrue(duplicates.quarantine && malformed.quarantine && unknown.quarantine,
            "Malformed/unknown must quarantine")
        assertTrue(duplicates.semanticSha256 == null && malformed.semanticSha256 == null &&
            unknown.semanticSha256 == null, "No semantic digest for RAW_BYTES")

        val h = T043MessageFingerprint.Envelope("node-A","m-17","node-B","hands",101,
            "poker.observation",schemaMajor=1,schemaMinor=0)
        assertEq(T043MessageFingerprint.envelopeSha256(h,raw("""{"b":2,"a":1}""")),
            "453841ac4667eec8db80513ec5a1b0e916b2acecbe685db1d635b42132abe2e5","V01 envelope")
        assertEq(T043MessageFingerprint.envelopeSha256(h,raw("""{"a":1,"b":2}""")),
            "453841ac4667eec8db80513ec5a1b0e916b2acecbe685db1d635b42132abe2e5","V01 envelope replay")
        assertEq(T043MessageFingerprint.envelopeSha256(h,duplicates),
            "881846193a11782442af0b4c2e28888472318a802cb02088316ad41f83ff10d3","V06 envelope")
        assertEq(T043MessageFingerprint.envelopeSha256(h,malformed),
            "6f06e4f154503eefeeefc89812a7012813c670d68434f756d763ef2632352ca7","V07 envelope")
        assertEq(T043MessageFingerprint.envelopeSha256(h.copy(canonicalizationId="json-semantic.v99"),unknown),
            "595b5bb294aa8fb9bc06160ec232830dbe06142f4279089c87c8046555055cff","V08 envelope")
        assertTrue(T043MessageFingerprint.envelopeSha256(h.copy(recipientId="different"),raw("""{"a":1}""")) !=
            T043MessageFingerprint.envelopeSha256(h,raw("""{"a":1}""")), "recipient is bound")
        assertTrue(raw("""{"a":1}""").rawSha256 != raw("""{"a":1 }""").rawSha256 &&
            raw("""{"a":1}""").semanticSha256 == raw("""{"a":1 }""").semanticSha256,
            "raw and semantic comparison differ")
        println("T043 fingerprint JVM scenarios: $passed PASS (8 raw/semantic + 4 envelope vectors)")
    }
}
