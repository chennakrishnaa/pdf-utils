package app.venkat.offkit

import java.io.File
import java.util.Base64

/**
 * Small password-protected one-page PDFs ([PAGE_PT] x [PAGE_PT] pt, the word [WORD] in Helvetica
 * 24 pt and a black square), checked in as base64 so the on-device tests can use them without
 * running MuPDF in the instrumentation process.
 *
 * Generated once on the JVM by MuPDF's save-with-encryption (`EncryptedFixturesTest.regenerate`
 * in `app/src/test`, which also checks these constants against real MuPDF on every unit-test run).
 * To regenerate: `OFFKIT_REGEN_ENCRYPTED_FIXTURES=1 ./gradlew :app:testDebugUnitTest --tests
 * app.venkat.offkit.engine.EncryptedFixturesTest --no-daemon` and paste
 * `app/build/encrypted-fixtures.kt.txt` over [AES256_B64], [RC4_B64] and [OWNER_ONLY_B64].
 *
 * The `AES128_*` constants are NOT made by MuPDF but by an independent writer, pypdf 7.2.0 (with
 * cryptography 50.0.2, from PyPI), so that MuPDF's reading is checked against someone else's
 * encryption: the same plaintext page (classic xref, the content stream
 * `BT /F1 24 Tf 20 120 Td (Zephyr) Tj ET 0 0 0 rg 20 20 60 60 re f`, Helvetica), loaded with
 * `PdfWriter(clone_from=PdfReader(plain))`, then `w.encrypt(user_password=U, owner_password=O,
 * algorithm="AES-128", permissions_flag=-4)` and `w.write(...)`; pypdf reads each file back
 * (`decrypt(U)`, text contains [WORD]) before it is encoded. The result is `/V 4 /R 4`,
 * `/CFM /AESV2`, `/Length 128`. pypdf writes `/P` unsigned (4294967292 for -4). R2-R4 passwords
 * are encoded by pypdf as Latin-1.
 */
object EncryptedFixtures {
    const val PAGE_PT = 200f
    const val WORD = "Zephyr"

    /** User password of [AES256_B64] (AES-256, R6): non-ASCII, BMP only. Owner password "owner-aes". */
    const val AES256_USER = "pässwörd-é"

    /** User password of [RC4_B64] (RC4 128-bit, R3). Owner password "owner-rc4". */
    const val RC4_USER = "rc4-secret"

    const val AES256_B64 =
        "JVBERi0xLjcKJcK1wrYKJSBXcml0dGVuIGJ5IE11UERGIDEuMjguNQoKMSAwIG9iago8PC9UeXBlL0NhdGFsb2cvUGFnZXMg" +
        "MiAwIFI+PgplbmRvYmoKCjIgMCBvYmoKPDwvVHlwZS9QYWdlcy9LaWRzWzMgMCBSXS9Db3VudCAxPj4KZW5kb2JqCgozIDAg" +
        "b2JqCjw8L1R5cGUvUGFnZS9QYXJlbnQgMiAwIFIvTWVkaWFCb3hbMCAwIDIwMCAyMDBdL0NvbnRlbnRzIDQgMCBSL1Jlc291" +
        "cmNlczw8L0ZvbnQ8PC9GMSA1IDAgUj4+Pj4+PgplbmRvYmoKCjQgMCBvYmoKPDwvTGVuZ3RoIDgwL0ZpbHRlci9GbGF0ZURl" +
        "Y29kZT4+CnN0cmVhbQoYtoGAQ6jk089nxXTig6KI/mCz6qvxadObG849UkMlgF/wf//0jtfLvP4x41Eq/O5hN7GrU6SRi1GZ" +
        "l0hZBXA9SrtknN3NB51xORrm3RJBdAplbmRzdHJlYW0KZW5kb2JqCgo1IDAgb2JqCjw8L1R5cGUvRm9udC9TdWJ0eXBlL1R5" +
        "cGUxL0Jhc2VGb250L0hlbHZldGljYT4+CmVuZG9iagoKeHJlZgowIDYKMDAwMDAwMDAwMCA2NTUzNSBmIAowMDAwMDAwMDQy" +
        "IDAwMDAwIG4gCjAwMDAwMDAwODggMDAwMDAgbiAKMDAwMDAwMDE0MCAwMDAwMCBuIAowMDAwMDAwMjUzIDAwMDAwIG4gCjAw" +
        "MDAwMDA0MDEgMDAwMDAgbiAKCnRyYWlsZXIKPDwvU2l6ZSA2L1Jvb3QgMSAwIFIvSURbPDI2REU0Q0IzNzk4QkZBNEQyNDRE" +
        "NkQyMzg0QURGNkE0Pjw2MkM0NzJERjM1QTQ1MDM2QjY1NkU0MTMwQzBFMzYyMT5dL0VuY3J5cHQ8PC9GaWx0ZXIvU3RhbmRh" +
        "cmQvUiA2L1YgNS9MZW5ndGggMjU2L1AgLTQvRW5jcnlwdE1ldGFkYXRhIHRydWUvU3RtRi9TdGRDRi9TdHJGL1N0ZENGL0NG" +
        "PDwvU3RkQ0Y8PC9BdXRoRXZlbnQvRG9jT3Blbi9DRk0vQUVTVjMvTGVuZ3RoIDMyPj4+Pi9PPDM0QkJBRkRFRUIxOUVDMUMw" +
        "QkQxQjg1N0M1NDkzOTc3NjEwNUY5RDgwQTBFNTg3RjQ2QTc1NTM2Q0JDMzkxRUIxNzQ2RUE4QTM1MDQ3MUNDMkE2OTk4REY5" +
        "MzU5NTZBQz4vVTw4RERFQkIxRURBNjlCQkY4Q0Y2ODhDQUY4Rjg0N0Y3MzYxMjdENjJFMEMyQkI4RTNGOTVGRDAyMjRCNzcz" +
        "RTBDMTc1ODU2RENBREZFNUQ2MEQ2RTkyM0Y4QjBBQTI3NEY+L09FPEMxNjY4Q0I5OUM4NDJCRUNBREE2QUQyMDg0QUNFODc4" +
        "NkM4Q0ExMjNERDg4QzU4OUY3NDRBRkY5OTUxRTQwMzA+L1VFPDQxMTZCNzNCNjhENDhGRDRCRURENDc1NEFBM0M1OUQ2QTg3" +
        "QzUwQzRFMUE2QkYwMDI2NjY5RUM5ODlBOENBRjU+L1Blcm1zPDhDMTgyQ0ZDMTcxMDc0RDMzQUUxQzE2RDJFM0VDODk2Pj4+" +
        "Pj4Kc3RhcnR4cmVmCjQ2NQolJUVPRgo="

    const val RC4_B64 =
        "JVBERi0xLjcKJcK1wrYKJSBXcml0dGVuIGJ5IE11UERGIDEuMjguNQoKMSAwIG9iago8PC9UeXBlL0NhdGFsb2cvUGFnZXMg" +
        "MiAwIFI+PgplbmRvYmoKCjIgMCBvYmoKPDwvVHlwZS9QYWdlcy9LaWRzWzMgMCBSXS9Db3VudCAxPj4KZW5kb2JqCgozIDAg" +
        "b2JqCjw8L1R5cGUvUGFnZS9QYXJlbnQgMiAwIFIvTWVkaWFCb3hbMCAwIDIwMCAyMDBdL0NvbnRlbnRzIDQgMCBSL1Jlc291" +
        "cmNlczw8L0ZvbnQ8PC9GMSA1IDAgUj4+Pj4+PgplbmRvYmoKCjQgMCBvYmoKPDwvTGVuZ3RoIDU5L0ZpbHRlci9GbGF0ZURl" +
        "Y29kZT4+CnN0cmVhbQpUnKeQcS0G6WK3We9JclYLPYPc3J8oAVVcQFZWh83v4M5I5MPsDUHPuHGFvFT2Ux/6u/r62fJcbl6q" +
        "lQplbmRzdHJlYW0KZW5kb2JqCgo1IDAgb2JqCjw8L1R5cGUvRm9udC9TdWJ0eXBlL1R5cGUxL0Jhc2VGb250L0hlbHZldGlj" +
        "YT4+CmVuZG9iagoKeHJlZgowIDYKMDAwMDAwMDAwMCA2NTUzNSBmIAowMDAwMDAwMDQyIDAwMDAwIG4gCjAwMDAwMDAwODgg" +
        "MDAwMDAgbiAKMDAwMDAwMDE0MCAwMDAwMCBuIAowMDAwMDAwMjUzIDAwMDAwIG4gCjAwMDAwMDAzODAgMDAwMDAgbiAKCnRy" +
        "YWlsZXIKPDwvU2l6ZSA2L1Jvb3QgMSAwIFIvSURbPERBQ0Q2OEE5MTVFNzM3RUUyQjVDRDA1OUUwQkNDRTA3Pjw2NjczRTU4" +
        "QkYwMzEyQURGMDQ4OTU1MjI4OTM1MEFBMD5dL0VuY3J5cHQ8PC9GaWx0ZXIvU3RhbmRhcmQvUiAzL1YgMi9MZW5ndGggMTI4" +
        "L1AgLTQvRW5jcnlwdE1ldGFkYXRhIHRydWUvTzw3RDUwNEIxOUUxOTZCNkQ0MTgzQTRDQjQ5QTBCODRBOUY0MEM5OTJDQjJB" +
        "ODlDNDgwNkM3NjJDOTczREY2N0E5Pi9VPEJGRTUxODM1NDEwNkREREFDMDI3OTgyMjJBOUMwOEExMjhCRjRFNUU0RTc1OEE0" +
        "MTY0MDA0RTU2RkZGQTAxMDg+Pj4+PgpzdGFydHhyZWYKNDQ0CiUlRU9GCg=="

    /** AES-256 with an empty user password and owner password "owner-only": opens without one. */
    const val OWNER_ONLY_B64 =
        "JVBERi0xLjcKJcK1wrYKJSBXcml0dGVuIGJ5IE11UERGIDEuMjguNQoKMSAwIG9iago8PC9UeXBlL0NhdGFsb2cvUGFnZXMg" +
        "MiAwIFI+PgplbmRvYmoKCjIgMCBvYmoKPDwvVHlwZS9QYWdlcy9LaWRzWzMgMCBSXS9Db3VudCAxPj4KZW5kb2JqCgozIDAg" +
        "b2JqCjw8L1R5cGUvUGFnZS9QYXJlbnQgMiAwIFIvTWVkaWFCb3hbMCAwIDIwMCAyMDBdL0NvbnRlbnRzIDQgMCBSL1Jlc291" +
        "cmNlczw8L0ZvbnQ8PC9GMSA1IDAgUj4+Pj4+PgplbmRvYmoKCjQgMCBvYmoKPDwvTGVuZ3RoIDgwL0ZpbHRlci9GbGF0ZURl" +
        "Y29kZT4+CnN0cmVhbQrLC2vSzUK5V3U/oM3/uvbQUBrneHj9vflPX032/M0JSPkCg77mxTn2pVN3nVOrPA6+SEjmyBkhGELl" +
        "N6oIuC/ueL2QYaFamkI/kkfFTA+mMgplbmRzdHJlYW0KZW5kb2JqCgo1IDAgb2JqCjw8L1R5cGUvRm9udC9TdWJ0eXBlL1R5" +
        "cGUxL0Jhc2VGb250L0hlbHZldGljYT4+CmVuZG9iagoKeHJlZgowIDYKMDAwMDAwMDAwMCA2NTUzNSBmIAowMDAwMDAwMDQy" +
        "IDAwMDAwIG4gCjAwMDAwMDAwODggMDAwMDAgbiAKMDAwMDAwMDE0MCAwMDAwMCBuIAowMDAwMDAwMjUzIDAwMDAwIG4gCjAw" +
        "MDAwMDA0MDEgMDAwMDAgbiAKCnRyYWlsZXIKPDwvU2l6ZSA2L1Jvb3QgMSAwIFIvSURbPEMzRDc2QTQwODM5RUZGQzU3RUFF" +
        "RUM4NDIwMjk4QjRGPjwwQzIxODJENjcwN0VCMTVEQUY3OTdFNzA4MTE4MUIzMD5dL0VuY3J5cHQ8PC9GaWx0ZXIvU3RhbmRh" +
        "cmQvUiA2L1YgNS9MZW5ndGggMjU2L1AgLTQvRW5jcnlwdE1ldGFkYXRhIHRydWUvU3RtRi9TdGRDRi9TdHJGL1N0ZENGL0NG" +
        "PDwvU3RkQ0Y8PC9BdXRoRXZlbnQvRG9jT3Blbi9DRk0vQUVTVjMvTGVuZ3RoIDMyPj4+Pi9PPEFDNkY4Qjg4MDU1NTJERDkw" +
        "RTM2NzgyOUNCNzZGNjg5Rjc3MDVBN0JERUZBRjY5QThDNzNCNjM4OTA2MjI3MjUwNkIwM0JGM0YxQ0MxMjM2RjVDNTVCRkEy" +
        "Rjg1RkQyNT4vVTxERUM1NjI2QUZEMEIzMEM4M0UzRDAxNjE5NDJFMDVFOTg4NTY1MTRGNEVGQjRBRjk0MzgxNjdFMzFFNTgy" +
        "QTdEQUE2ODhEQ0Q4NUFGOURGQTFEQTBGRjFGREZBNUZCM0M+L09FPERFQjdDQjJDMDczQ0UzNjg0MTM4MEZEMTA0OTEzQ0VG" +
        "QzI5MDU0RDRDOTlCRTEyRkRERjRFQjBENUVCQTFEMEQ+L1VFPEFDQjAyNThCNjlDOEI4QzM0MkU5MzlGREUwRDk0RTU5QTM5" +
        "RUUwNUYxNEU5MUREQUZCMzQwMENCRDAxQzgwNDM+L1Blcm1zPEMxNjJFRTNGQTRFMUI4RUFFNjJBRkY0MUE3RTRDRDU4Pj4+" +
        "Pj4Kc3RhcnR4cmVmCjQ2NQolJUVPRgo="

    /** User password of [AES128_B64] (AES-128, V4 R4, pypdf). Owner password "owner-aes128". */
    const val AES128_USER = "aes128-secret"

    const val AES128_B64 =
        "JVBERi0xLjMKJeLjz9MKMSAwIG9iago8PAovVHlwZSAvQ2F0YWxvZwovUGFnZXMgMiAwIFIKPj4KZW5kb2JqCjIgMCBvYmoK" +
        "PDwKL1R5cGUgL1BhZ2VzCi9LaWRzIFsgMyAwIFIgXQovQ291bnQgMQo+PgplbmRvYmoKMyAwIG9iago8PAovVHlwZSAvUGFn" +
        "ZQovUGFyZW50IDIgMCBSCi9NZWRpYUJveCBbIDAgMCAyMDAgMjAwIF0KL0NvbnRlbnRzIDQgMCBSCi9SZXNvdXJjZXMgPDwK" +
        "L0ZvbnQgPDwKL0YxIDUgMCBSCj4+Cj4+Cj4+CmVuZG9iago0IDAgb2JqCjw8Ci9MZW5ndGggODAKPj4Kc3RyZWFtCoXL8sTq" +
        "o5oDTSrl3RAj6zj64jUjNiqC9Py2MmyXMW7dI7NxBg7a9BB1mFEjqSbEYsz4i6+gZ35pAxcMPA9F2r95aiFjyPOvsyKf8rXl" +
        "PaVQCmVuZHN0cmVhbQplbmRvYmoKNSAwIG9iago8PAovVHlwZSAvRm9udAovU3VidHlwZSAvVHlwZTEKL0Jhc2VGb250IC9I" +
        "ZWx2ZXRpY2EKPj4KZW5kb2JqCjYgMCBvYmoKPDwKL1YgNAovUiA0Ci9MZW5ndGggMTI4Ci9QIDQyOTQ5NjcyOTIKL0ZpbHRl" +
        "ciAvU3RhbmRhcmQKL08gPGVmM2IwOTc0ZTgyY2QzMzhkMzYxMmEwMjNlYTg2ZjdjYjE2Y2NkOTNhZWVhNTEwZGI5MGQ0ZGI0" +
        "MzU2OWFjMGY+Ci9VIDxiZDU2M2I3ODQ4N2U2NmJhM2VjZjVkMzM4NDU3YzlmZjI4YmY0ZTVlNGU3NThhNDE2NDAwNGU1NmZm" +
        "ZmEwMTA4PgovQ0YgPDwKL1N0ZENGIDw8Ci9BdXRoRXZlbnQgL0RvY09wZW4KL0NGTSAvQUVTVjIKL0xlbmd0aCAxNgo+Pgo+" +
        "PgovU3RtRiAvU3RkQ0YKL1N0ckYgL1N0ZENGCj4+CmVuZG9iagp4cmVmCjAgNwowMDAwMDAwMDAwIDY1NTM1IGYgCjAwMDAw" +
        "MDAwMTUgMDAwMDAgbiAKMDAwMDAwMDA2NCAwMDAwMCBuIAowMDAwMDAwMTIzIDAwMDAwIG4gCjAwMDAwMDAyNTEgMDAwMDAg" +
        "biAKMDAwMDAwMDM4MSAwMDAwMCBuIAowMDAwMDAwNDUxIDAwMDAwIG4gCnRyYWlsZXIKPDwKL1NpemUgNwovUm9vdCAxIDAg" +
        "UgovSUQgWyA8ZGMyNzVlMjRkZGIxYjE0MmEwYzEzY2ExZTFjZGM1NGE+IDxkYzI3NWUyNGRkYjFiMTQyYTBjMTNjYTFlMWNk" +
        "YzU0YT4gXQovRW5jcnlwdCA2IDAgUgo+PgpzdGFydHhyZWYKNzU4CiUlRU9GCg=="

    /** AES-128 (V4 R4, pypdf) with an empty user password and owner password "owner-only". */
    const val AES128_OWNER_ONLY_B64 =
        "JVBERi0xLjMKJeLjz9MKMSAwIG9iago8PAovVHlwZSAvQ2F0YWxvZwovUGFnZXMgMiAwIFIKPj4KZW5kb2JqCjIgMCBvYmoK" +
        "PDwKL1R5cGUgL1BhZ2VzCi9LaWRzIFsgMyAwIFIgXQovQ291bnQgMQo+PgplbmRvYmoKMyAwIG9iago8PAovVHlwZSAvUGFn" +
        "ZQovUGFyZW50IDIgMCBSCi9NZWRpYUJveCBbIDAgMCAyMDAgMjAwIF0KL0NvbnRlbnRzIDQgMCBSCi9SZXNvdXJjZXMgPDwK" +
        "L0ZvbnQgPDwKL0YxIDUgMCBSCj4+Cj4+Cj4+CmVuZG9iago0IDAgb2JqCjw8Ci9MZW5ndGggODAKPj4Kc3RyZWFtCvdgLXYt" +
        "2oeAhkcdcQelYY1YE/Q+k4L4izzZfT0Hrqs1Pl+AWcYWfyTaSeZxlghtqeyBlcZL+F5o7lvL2H1n11xfZhBiMkc87KgvVk1C" +
        "pUTbCmVuZHN0cmVhbQplbmRvYmoKNSAwIG9iago8PAovVHlwZSAvRm9udAovU3VidHlwZSAvVHlwZTEKL0Jhc2VGb250IC9I" +
        "ZWx2ZXRpY2EKPj4KZW5kb2JqCjYgMCBvYmoKPDwKL1YgNAovUiA0Ci9MZW5ndGggMTI4Ci9QIDQyOTQ5NjcyOTIKL0ZpbHRl" +
        "ciAvU3RhbmRhcmQKL08gPDg5ZGYzMGZkYzc2MDhiYmM4OTgxZGI5MjU5MWMxZTc2Zjg3MzY2OWY2N2FiNjNhMmVmZDk5ZGE2" +
        "NzczMWMyYmU+Ci9VIDw1Y2MwZGI1MGM5NmMzZDUxZWQ3ZmY5MTA3YzMxNDE1NTI4YmY0ZTVlNGU3NThhNDE2NDAwNGU1NmZm" +
        "ZmEwMTA4PgovQ0YgPDwKL1N0ZENGIDw8Ci9BdXRoRXZlbnQgL0RvY09wZW4KL0NGTSAvQUVTVjIKL0xlbmd0aCAxNgo+Pgo+" +
        "PgovU3RtRiAvU3RkQ0YKL1N0ckYgL1N0ZENGCj4+CmVuZG9iagp4cmVmCjAgNwowMDAwMDAwMDAwIDY1NTM1IGYgCjAwMDAw" +
        "MDAwMTUgMDAwMDAgbiAKMDAwMDAwMDA2NCAwMDAwMCBuIAowMDAwMDAwMTIzIDAwMDAwIG4gCjAwMDAwMDAyNTEgMDAwMDAg" +
        "biAKMDAwMDAwMDM4MSAwMDAwMCBuIAowMDAwMDAwNDUxIDAwMDAwIG4gCnRyYWlsZXIKPDwKL1NpemUgNwovUm9vdCAxIDAg" +
        "UgovSUQgWyA8M2YxMzM5YmE3MGU0MzAzZjczN2QxNzQxMmM0YTAxYzY+IDwzZjEzMzliYTcwZTQzMDNmNzM3ZDE3NDEyYzRh" +
        "MDFjNj4gXQovRW5jcnlwdCA2IDAgUgo+PgpzdGFydHhyZWYKNzU4CiUlRU9GCg=="

    fun bytes(b64: String): ByteArray = Base64.getDecoder().decode(b64)

    fun write(dir: File, name: String, b64: String): File = File(dir, name).also { it.writeBytes(bytes(b64)) }
}
