resolvers ++= Seq("typesafe-releases" at "http://repo.typesafe.com/typesafe/releases",
                  "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository")

libraryDependencies ++= Seq(
  "org.specs2"   %% "specs2"         % "2.3.11" % "test",
  "org.mockito"  % "mockito-all"     % "1.9.5" % "test",
  "org.json4s"   % "json4s-native_2.10"  % "3.2.6",
  "org.json4s"   % "json4s-jackson_2.10" % "3.2.6",
  "com.github.scopt" %% "scopt" % "3.3.0"
)

initialCommands := """
  import au.com.dius.pact.model._;
  import au.com.dius.pact.server._;
"""
