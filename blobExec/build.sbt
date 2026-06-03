name := "blobExec"

version := "0.1.0"

scalaVersion := "2.13.16"

libraryDependencies ++= Seq(
  "com.madgag" %% "bfg-library" % "1.15.0"
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
