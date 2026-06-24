name := "blobExec"

version := "0.1.0"

scalaVersion := "2.13.16"

libraryDependencies ++= Seq(
  // bfg-library is kept only for the FormerCommitFooter cross-check test and
  // because it transitively pulls in the jgit version we walk against. The
  // production code no longer uses RepoRewriter / ObjectIdCleaner.
  "com.madgag" %% "bfg-library" % "1.15.0",
  "org.xerial" % "sqlite-jdbc" % "3.46.1.0",
  "org.scalatest" %% "scalatest" % "3.2.19" % Test
)

assembly / assemblyJarName := s"blobExec-${version.value}-assembly.jar"

assembly / mainClass := Some("cregit.blobexec.Main")

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".SF")) => MergeStrategy.discard
  case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".DSA")) => MergeStrategy.discard
  case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".RSA")) => MergeStrategy.discard
  case PathList("module-info.class") => MergeStrategy.discard
  case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
  case x => (assembly / assemblyMergeStrategy).value(x)
}
