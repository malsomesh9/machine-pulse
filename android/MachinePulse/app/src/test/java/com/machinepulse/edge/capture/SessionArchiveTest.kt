package com.machinepulse.edge.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

class SessionArchiveTest {
    @Test
    fun archiveContainsOnlyTheThreeSessionArtifacts() {
        val root = Files.createTempDirectory("machinepulse-session-test").toFile()
        val session = root.resolve("baseline_test").also { it.mkdirs() }
        session.resolve("accelerometer.csv").writeText("timestamp_ns,x\n1,0.5\n")
        session.resolve("audio.wav").writeBytes(byteArrayOf(1, 2, 3))
        session.resolve("metadata.json").writeText("{\"outcome\":\"completed\"}")
        val archive = root.resolve("session.zip")

        zipSessionDirectory(session, archive)

        ZipFile(archive).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertEquals(
                setOf("accelerometer.csv", "audio.wav", "metadata.json"),
                names,
            )
            assertEquals(
                "{\"outcome\":\"completed\"}",
                zip.getInputStream(zip.getEntry("metadata.json")).bufferedReader().readText(),
            )
        }
        root.deleteRecursively()
    }
}
