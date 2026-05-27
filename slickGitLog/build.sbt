import com.github.retronym.SbtOneJar._

oneJarSettings


libraryDependencies ++= Seq(
  "com.typesafe.slick" %% "slick" % "3.0.0",
  "org.xerial" % "sqlite-jdbc" % "3.45.3.0",
  "com.zaxxer" % "HikariCP" % "2.4.1",
  "org.eclipse.jgit" % "org.eclipse.jgit" % "4.6.0.201612231935-r"
)

resolvers ++= Seq(
  "jgit-repo" at "https://download.eclipse.org/jgit/maven"
)

