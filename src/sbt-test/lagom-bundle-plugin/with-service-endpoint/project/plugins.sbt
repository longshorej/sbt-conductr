addSbtPlugin("com.lightbend.lagom" % "lagom-sbt-plugin" % "1.2.2")

addSbtPlugin("com.lightbend.conductr" % "sbt-conductr" % sys.props("project.version"))
libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.6"
