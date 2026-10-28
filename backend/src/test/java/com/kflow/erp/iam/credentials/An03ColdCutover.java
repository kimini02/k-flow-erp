package com.kflow.erp.iam.credentials;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Isolated test orchestration only. This is NOT an operating deployment control. */
final class An03ColdCutover {
  static final String BASE = "f9bbfad26cea210c91e63e58a58e3fe5a1022ddc";

  static Path previousArtifact(Path temporary) throws Exception {
    Path source = temporary.resolve("an02-source");
    Files.createDirectories(source);
    Path log = temporary.resolve("an02-build.log");
    var archive =
        new ProcessBuilder("git", "archive", BASE, "backend")
            .directory(Path.of("..").toFile())
            .redirectError(log.toFile());
    var unpack =
        new ProcessBuilder("tar", "-x", "-C", source.toString())
            .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
    for (var child : ProcessBuilder.startPipeline(List.of(archive, unpack))) {
      if (!child.waitFor(20, TimeUnit.SECONDS) || child.exitValue() != 0)
        throw new AssertionError(
            "Pinned AN-02 source unavailable; checkout full Git history for cold-cutover test");
    }
    var build =
        new ProcessBuilder("./mvnw", "-B", "-ntp", "-o", "-Dmaven.test.skip=true", "package")
            .directory(source.resolve("backend").toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .start();
    if (!build.waitFor(90, TimeUnit.SECONDS)) {
      build.destroyForcibly().waitFor();
      throw new AssertionError("Pinned AN-02 artifact build timed out");
    }
    if (build.exitValue() != 0)
      throw new AssertionError(
          "Pinned AN-02 offline build failed; inspect isolated test build log");
    Path jar = source.resolve("backend/target/k-flow-erp-0.0.1-SNAPSHOT.jar");
    try (var zip = new java.util.jar.JarFile(jar.toFile())) {
      if (zip.getEntry(
              "BOOT-INF/classes/com/kflow/erp/iam/application/authentication/RequestIdentityResolver.class")
          != null) throw new AssertionError("Expected actual pre-AN03 artifact");
    }
    System.out.println(
        "A3 T33/T34 built actual pinned AN-02 artifact BASE=" + BASE + " SHA256=" + digest(jar));
    return jar;
  }

  static String digest(Path path) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }

  static void requireColdGate(
      Path candidate, String approvedDigest, List<Process> oldProcesses, long sessionRows)
      throws Exception {
    if (!digest(candidate).equals(approvedDigest))
      throw new IllegalStateException("Unapproved/old artifact blocked");
    if (oldProcesses.stream().anyMatch(Process::isAlive))
      throw new IllegalStateException("Drain/stop incomplete");
    if (sessionRows != 0) throw new IllegalStateException("Session purge incomplete");
  }
}
