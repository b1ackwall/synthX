package com.v1per.synthx.data.scanner

import java.util.Base64

/**
 * A real Ogg Opus file, kept as base64 so no binary audio is checked in. All of it is synthetic: one
 * second of a 440 Hz sine encoded by ffmpeg's libopus at 24 kbps mono, with the Vorbis comments
 * TITLE, ARTIST, ALBUM and TRACKNUMBER below plus DATE=2021, and a 32x32 PNG of [COVER_ARGB] as a
 * front-cover (type 3) METADATA_BLOCK_PICTURE comment.
 */
internal object OpusAudioFixture {
    const val TITLE = "Opus Title"
    const val ARTIST = "Opus Artist"
    const val ALBUM = "Opus Album"
    const val TRACK_NUMBER = 7

    /** The cover's only colour, opaque. */
    const val COVER_ARGB = 0xFFC83232

    fun bytes(): ByteArray = Base64.getDecoder().decode(BASE64.filterNot(Char::isWhitespace))

    private const val BASE64 = """
        T2dnUwACAAAAAAAAAAC+cX6NAAAAAFOXvugBE09wdXNIZWFkAQE4AYC7AAAAAABPZ2dTAAAAAAAAAAAAAL5xfo0BAAAA/IdPOwL/
        lk9wdXNUYWdzBgAAAGZmbXBlZwcAAAAUAAAAZW5jb2Rlcj1MYXZjIGxpYm9wdXMQAAAAVElUTEU9T3B1cyBUaXRsZRIAAABBUlRJ
        U1Q9T3B1cyBBcnRpc3QQAAAAQUxCVU09T3B1cyBBbGJ1bQ0AAABUUkFDS05VTUJFUj03CQAAAERBVEU9MjAyMQcBAABNRVRBREFU
        QV9CTE9DS19QSUNUVVJFPUFBQUFBd0FBQUFscGJXRm5aUzl3Ym1jQUFBQUFBQUFBSUFBQUFDQUFBQUFZQUFBQUFBQUFBSXFKVUU1
        SERRb2FDZ0FBQUExSlNFUlNBQUFBSUFBQUFDQUlBZ0FBQVB3WTdhTUFBQUFKY0VoWmN3QUFBQUVBQUFBQkFFOGx4TllBQUFBOFNV
        UkJWSGljN2RGQkRRQXdDQVRCSTZtZzFyOEFiRlhDOHVHM0krQklsdXI3c3Vtc3JzY0RBLzRBbVFpWkNKa0ltUWlaQ0prSW1RaVpL
        T1FEREVVQnB2VGNYOWdBQUFBQVNVVk9SSzVDWUlJPU9nZ1MAAIC7AAAAAAAAvnF+jQIAAADFcaz7Ml9COzg7PkhCRS42MywxNjAu
        UFBOTk5OTk5QUVFSU1ZVVVVVVlVVVVVWVVVVVVZVVVVVeIGnXWyemawAAAgK4FrVEZxEMRUFez3mfhy4+Q1ZWV8h0Lo6pY51ODwa
        GB+5+vFq2fXVcJFPCzga/phDsLVDqvBZa+L0NQ4NAGXEDRLh4XJsQY0ykTiyyEiUgOWl6gR4n2cB5/yVTU6qGKcZ3+VcWd/7bqEz
        ZfWopUkN5Fd3h48KQtCSRpH9H9Q7cD/31ZeoOFaCUiVwC7qWPBwPT0H3ib14mrLfdZz8SzpFfihacofGg4g7HEcqFigYE0QB/7oP
        k8Kx4XXYo/yU0WxOgXG28yAjp5/11uf3LN18BXiast91nPxF7dcznxNj3xAT2Q/vwLeheLwnaLmJv2ivyIrw2Qcyo8ih6dUeJsff
        xQ6td29ZSxNMeJqy33Wc/Ekzv0r3JSNopo0gAXV2m+0756UlqhIEP+7r70ZMlokgWFh/Z/dfvzje/FaX+Cmna/ZJ5QR4mo3gkr+7
        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
        ckaX6YyVsKkY/uWQrA==
    """
}
